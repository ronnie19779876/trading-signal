package org.jdkxx.trader.core.signal.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jdkxx.trader.domain.FinancialReport;
import org.jdkxx.trader.domain.FinancialStatement;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.SecurityType;
import org.jdkxx.trader.domain.selection.EarningsEvent;
import org.jdkxx.trader.domain.selection.EarningsTiming;
import org.jdkxx.trader.domain.signal.GateResult;
import org.jdkxx.trader.domain.signal.SentinelEvaluation;
import org.jdkxx.trader.storage.marketdata.CompanyProfileRepository;
import org.jdkxx.trader.storage.marketdata.DailyBarRepository;
import org.jdkxx.trader.storage.marketdata.FinancialRepository;
import org.jdkxx.trader.storage.marketdata.IndexConstituentRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.jdkxx.trader.storage.marketdata.RehabFactorRepository;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;
import org.jdkxx.trader.storage.marketdata.ValuationRepository;
import org.jdkxx.trader.storage.selection.SelectionEarningsRepository;
import org.junit.jupiter.api.Test;

/**
 * Regression (found in the 2026-10-31 review): the announcement filter on financials only ran for
 * judgement dates more than 7 days back, so a judgement date a few days back (or today with an
 * after-market announcement on the day) was sent a quarter that was not public yet on that day.
 */
class SignalPayloadBuilderTest {

  private static final Instrument X = Instrument.us("X");
  private static final InstrumentRow ROW =
      new InstrumentRow(
          1, Market.US, "X", "X", null, SecurityType.STOCK, 1, null, false, null, null, "RESOLVED");

  private final FinancialRepository financials = mock(FinancialRepository.class);
  private final SelectionEarningsRepository earnings = mock(SelectionEarningsRepository.class);
  private final TradingDayRepository days = mock(TradingDayRepository.class);
  private final SignalPayloadBuilder builder =
      new SignalPayloadBuilder(
          mock(InstrumentRepository.class),
          mock(IndexConstituentRepository.class),
          mock(DailyBarRepository.class),
          mock(RehabFactorRepository.class),
          days,
          mock(ValuationRepository.class),
          financials,
          mock(CompanyProfileRepository.class),
          earnings,
          ZoneId.of("America/New_York"));

  private static LocalDate d(String s) {
    return LocalDate.parse(s);
  }

  private static FinancialReport report(String periodEnd, String periodText) {
    return new FinancialReport(
        X, FinancialStatement.INCOME, d(periodEnd), 2026, periodText, "USD", null, null, List.of());
  }

  private static SentinelEvaluation evaluation(LocalDate asOf) {
    return new SentinelEvaluation(
        "sentinel-v1",
        asOf,
        SentinelEvaluation.Status.EVALUATED,
        null,
        List.of(
            new GateResult(
                GateResult.Gate.RISK,
                GateResult.Verdict.PASS,
                "",
                Map.of("stop", 90.0, "stopDistance", 0.05))),
        Map.of("close", 100.0),
        List.of(),
        null,
        null,
        Map.of());
  }

  private void setUp(EarningsTiming q3Timing) {
    when(days.between(eq(Market.US), any(), any()))
        .thenAnswer(
            inv -> {
              List<LocalDate> out = new ArrayList<>();
              LocalDate to = inv.getArgument(2);
              for (LocalDate x = inv.getArgument(1); !x.isAfter(to); x = x.plusDays(1)) {
                if (x.getDayOfWeek() != DayOfWeek.SATURDAY
                    && x.getDayOfWeek() != DayOfWeek.SUNDAY) {
                  out.add(x);
                }
              }
              return out;
            });
    // Stored period ends are one day before the true ones (§25.5)
    when(financials.find(any(), anyLong(), eq(FinancialStatement.INCOME), anyInt()))
        .thenReturn(List.of(report("2026-09-29", "2026/Q3"), report("2026-06-29", "2026/Q2")));
    when(earnings.events(1L))
        .thenReturn(
            List.of(
                new EarningsEvent(
                    "2026/Q2", 2026, d("2026-07-29"), null, EarningsTiming.PRE_MARKET, false),
                new EarningsEvent("2026/Q3", 2026, d("2026-10-28"), null, q3Timing, true)));
  }

  @SuppressWarnings("unchecked")
  private static List<String> quarters(Map<String, Object> payload) {
    Map<String, Object> fin = (Map<String, Object>) payload.get("financials");
    return ((List<Map<String, Object>>) fin.get("quarters"))
        .stream().map(q -> (String) q.get("period")).toList();
  }

  @Test
  void 判定日是几天前时_之后才公布的季度不给() {
    setUp(EarningsTiming.PRE_MARKET);
    // asOf 10-27, today 10-30: Q3 was announced on 10-28, after the judgement date
    Map<String, Object> p = builder.build(ROW, evaluation(d("2026-10-27")), d("2026-10-30"));
    assertThat(quarters(p)).containsExactly("2026/Q2");
  }

  @Test
  void 判定日是今天且当天盘后公布时_这一季不给() {
    setUp(EarningsTiming.AFTER_MARKET);
    Map<String, Object> p = builder.build(ROW, evaluation(d("2026-10-28")), d("2026-10-28"));
    assertThat(quarters(p)).containsExactly("2026/Q2");
  }

  @Test
  void 判定日是今天且当天盘前已公布时_这一季照给() {
    setUp(EarningsTiming.PRE_MARKET);
    Map<String, Object> p = builder.build(ROW, evaluation(d("2026-10-28")), d("2026-10-28"));
    assertThat(quarters(p)).containsExactly("2026/Q3", "2026/Q2");
  }
}
