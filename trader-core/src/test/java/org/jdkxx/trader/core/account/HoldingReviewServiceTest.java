package org.jdkxx.trader.core.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jdkxx.trader.core.marketdata.audit.BarAuditService.Check;
import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.PoolRole;
import org.jdkxx.trader.domain.SecurityType;
import org.jdkxx.trader.domain.selection.EarningsEvent;
import org.jdkxx.trader.domain.selection.EarningsTiming;
import org.jdkxx.trader.storage.account.AccountSnapshotRepository;
import org.jdkxx.trader.storage.account.AccountSnapshotRow;
import org.jdkxx.trader.storage.account.PositionSnapshotRow;
import org.jdkxx.trader.storage.marketdata.DailyBarRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.jdkxx.trader.storage.marketdata.PoolRepository;
import org.jdkxx.trader.storage.marketdata.PoolRow;
import org.jdkxx.trader.storage.marketdata.RehabFactorRepository;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;
import org.jdkxx.trader.storage.selection.SelectionEarningsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Scenario modelled on the day GOOG was added to on 2026-10-20: the position is already below the
 * 200-day average and below the average cost, and earnings come 6 trading days later (10-28 after
 * the close). The daily bars are synthetic: 300 weekdays rising then falling, with two historical
 * earnings reactions of -10% and +5%. The calendar is made of weekdays (holidays not removed).
 */
class HoldingReviewServiceTest {

  private static final ZoneId ET = ZoneId.of("America/New_York");
  private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-10-20T22:00:00Z"), ZoneOffset.UTC);
  private static final InstrumentRow GOOG =
      new InstrumentRow(
          21,
          Market.US,
          "GOOG",
          "Alphabet",
          null,
          SecurityType.STOCK,
          1,
          null,
          false,
          null,
          null,
          "RESOLVED");
  private static final InstrumentRow MSFT =
      new InstrumentRow(
          22,
          Market.US,
          "MSFT",
          "Microsoft",
          null,
          SecurityType.STOCK,
          1,
          null,
          false,
          null,
          null,
          "RESOLVED");

  private final AccountSnapshotRepository snapshots = mock(AccountSnapshotRepository.class);
  private final PoolRepository pool = mock(PoolRepository.class);
  private final InstrumentRepository instruments = mock(InstrumentRepository.class);
  private final DailyBarRepository bars = mock(DailyBarRepository.class);
  private final RehabFactorRepository rehabs = mock(RehabFactorRepository.class);
  private final TradingDayRepository days = mock(TradingDayRepository.class);
  private final SelectionEarningsRepository earnings = mock(SelectionEarningsRepository.class);

  private List<LocalDate> dates;
  private LocalDate dropPub;

  private static List<LocalDate> weekdays(LocalDate from, LocalDate to) {
    List<LocalDate> out = new ArrayList<>();
    for (LocalDate x = from; !x.isAfter(to); x = x.plusDays(1)) {
      if (x.getDayOfWeek() != DayOfWeek.SATURDAY && x.getDayOfWeek() != DayOfWeek.SUNDAY) {
        out.add(x);
      }
    }
    return out;
  }

  private static DailyBar bar(Instrument i, LocalDate d, double open, double close) {
    BigDecimal o = BigDecimal.valueOf(open);
    BigDecimal c = BigDecimal.valueOf(close);
    return new DailyBar(
        i,
        d,
        o,
        o.max(c).add(BigDecimal.valueOf(0.5)),
        o.min(c).subtract(BigDecimal.valueOf(0.5)),
        c,
        o,
        1000,
        c,
        null,
        null,
        null,
        false);
  }

  private static List<DailyBar> series(Instrument i, List<LocalDate> dates, double[] closes) {
    List<DailyBar> out = new ArrayList<>();
    for (int k = 0; k < closes.length; k++) {
      out.add(bar(i, dates.get(k), k == 0 ? closes[0] : closes[k - 1], closes[k]));
    }
    return out;
  }

  @BeforeEach
  void setUp() {
    List<LocalDate> all = weekdays(TODAY.minusYears(2), TODAY);
    dates = all.subList(all.size() - 300, all.size());
    double[] goog = new double[300];
    for (int k = 0; k < 280; k++) {
      goog[k] = 100 + k * 0.3;
    }
    for (int k = 280; k < 300; k++) {
      goog[k] = 150;
    }
    goog[201] = goog[200] * 0.90; // announced after the close of day 200, -10% the next day
    goog[141] = goog[140] * 1.05; // announced after the close of day 140, +5% the next day
    dropPub = dates.get(200);
    double[] msft = new double[300];
    for (int k = 0; k < 300; k++) {
      msft[k] = 100 + k * 0.3;
    }

    when(days.between(eq(Market.US), any(), any()))
        .thenAnswer(inv -> weekdays(inv.getArgument(1), inv.getArgument(2)));
    when(bars.find(any(), eq(21L), any(), any()))
        .thenReturn(series(GOOG.instrument(), dates, goog));
    when(bars.find(any(), eq(22L), any(), any()))
        .thenReturn(series(MSFT.instrument(), dates, msft));
    when(rehabs.find(any(), any(Long.class))).thenReturn(List.of());
    when(instruments.findById(21L)).thenReturn(Optional.of(GOOG));
    when(instruments.findById(22L)).thenReturn(Optional.of(MSFT));
    when(instruments.find(Instrument.us("GOOG"))).thenReturn(Optional.of(GOOG));
    when(instruments.find(Instrument.us("MSFT"))).thenReturn(Optional.of(MSFT));
    when(earnings.events(21L))
        .thenReturn(
            List.of(
                new EarningsEvent(
                    "2026/Q1", 2026, dates.get(140), null, EarningsTiming.AFTER_MARKET, false),
                new EarningsEvent(
                    "2026/Q2", 2026, dropPub, null, EarningsTiming.AFTER_MARKET, false),
                new EarningsEvent(
                    "2026/Q3",
                    2026,
                    LocalDate.of(2026, 10, 28),
                    null,
                    EarningsTiming.AFTER_MARKET,
                    true)));
    when(earnings.events(22L)).thenReturn(List.of());
    AccountSnapshotRow snap =
        new AccountSnapshotRow(
            1,
            "IBKR",
            "k",
            "AC*****",
            TODAY,
            Instant.EPOCH,
            "USD",
            BigDecimal.valueOf(500_000),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            1,
            "OK",
            "[]",
            7L);
    when(snapshots.latest()).thenReturn(Optional.of(snap));
    when(snapshots.positions(1L))
        .thenReturn(
            List.of(
                new PositionSnapshotRow(
                    "208813720",
                    "GOOG",
                    21L,
                    "STK",
                    "USD",
                    "NASDAQ",
                    BigDecimal.valueOf(162),
                    BigDecimal.valueOf(180),
                    BigDecimal.valueOf(150),
                    "BAR",
                    BigDecimal.valueOf(24_300),
                    null,
                    null,
                    false)));
    when(pool.findAll())
        .thenReturn(
            List.of(
                new PoolRow(21, PoolRole.HOLDING, null, Instant.EPOCH),
                new PoolRow(22, PoolRole.POOL, null, Instant.EPOCH)));
  }

  private HoldingReviewService service(BigDecimal budget) {
    return new HoldingReviewService(
        new HoldingReviewProperties(10, 1.5, budget, 50, 3, true, true),
        snapshots,
        pool,
        instruments,
        bars,
        rehabs,
        days,
        earnings,
        CLOCK,
        ET);
  }

  private static HoldingReviewService.Item item(HoldingReviewService.Review r, String symbol) {
    return r.items().stream().filter(i -> i.symbol().equals(symbol)).findFirst().orElseThrow();
  }

  @Test
  void 持仓的财报预警与压力情景() {
    HoldingReviewService.Item g = item(service(new BigDecimal("0.5")).review(null), "GOOG");

    assertThat(g.role()).isEqualTo("HOLDING");
    assertThat(g.weightPct()).isEqualTo(4.86);
    HoldingReviewService.Upcoming next = g.earnings().next();
    assertThat(next.date()).isEqualTo(LocalDate.of(2026, 10, 28));
    assertThat(next.tradingDaysAhead()).isEqualTo(6);
    assertThat(next.inWindow()).isTrue();
    assertThat(next.estimated()).isFalse();
    assertThat(g.earnings().stats().samples()).isEqualTo(2);
    assertThat(g.earnings().stats().maxDropPct()).isEqualTo(10.0);
    assertThat(g.earnings().samples())
        .extracting(HoldingReviewService.SampleView::pubDate)
        .contains(dropPub);
    HoldingReviewService.Scenario sc = g.earnings().scenario();
    assertThat(sc.stressDropPct()).isEqualTo(15.0);
    assertThat(sc.historicalLoss()).isEqualTo(-2430.0);
    assertThat(sc.stressLoss()).isEqualTo(-3645.0);
    assertThat(sc.stressLossPctOfNav()).isEqualTo(0.73);
    assertThat(sc.overBudget()).isTrue();
    assertThat(g.maxWeightPctInBudget()).isEqualTo(3.33);
    assertThat(g.trend().state()).isEqualTo("BROKEN");
    assertThat(g.trend().pnlVsCostPct()).isEqualTo(-16.67);
    assertThat(g.triggers())
        .extracting(HoldingReviewService.Flag::trigger)
        .contains("BREAK_CONFIRMED", "CHANDELIER_BREACH", "EARNINGS_OVER_BUDGET")
        .doesNotContain("DEATH_CROSS");
  }

  /**
   * The loss budget is the user's choice: when unset there is no over-budget check, no position
   * sizing, and no "earnings over budget" review condition.
   */
  @Test
  void 没设预算就不判超预算() {
    HoldingReviewService.Item g = item(service(null).review(null), "GOOG");

    assertThat(g.earnings().scenario().overBudget()).isNull();
    assertThat(g.earnings().scenario().stressLoss()).isEqualTo(-3645.0);
    assertThat(g.maxWeightPctInBudget()).isNull();
    assertThat(g.triggers())
        .extracting(HoldingReviewService.Flag::trigger)
        .doesNotContain("EARNINGS_OVER_BUDGET");
  }

  @Test
  void 池成员只给趋势与分布不给金额() {
    HoldingReviewService.Item m = item(service(new BigDecimal("0.5")).review(null), "MSFT");

    assertThat(m.role()).isEqualTo("POOL");
    assertThat(m.marketValue()).isNull();
    assertThat(m.triggers()).isEmpty();
    assertThat(m.trend().state()).isEqualTo("UP_STRONG");
    assertThat(m.earnings().next()).isNull();
    assertThat(m.earnings().stats()).isNull();
  }

  /** 10-20 GOOG adding 100 shares: all four rules fail. */
  @Test
  void 加仓检查逐条给出() {
    HoldingReviewService.AddCheck c = service(new BigDecimal("0.5")).addCheck("goog", 100);

    assertThat(c.held()).isTrue();
    assertThat(c.allPass()).isFalse();
    assertThat(c.rules())
        .extracting(HoldingReviewService.Rule::rule, HoldingReviewService.Rule::status)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("TREND", "FAIL"),
            org.assertj.core.groups.Tuple.tuple("EARNINGS_WINDOW", "FAIL"),
            org.assertj.core.groups.Tuple.tuple("LOSS_BUDGET", "FAIL"),
            org.assertj.core.groups.Tuple.tuple("NO_AVERAGING_DOWN", "FAIL"));
    assertThat(c.conclusion()).isEqualTo("不满足你设定的加仓条件：趋势门、财报窗口、风险预算、不逆势摊低");
    // after adding, 262 shares x 150 = 39,300; x 15% = 5,895 > budget 2,500
    assertThat(c.rules().get(2).detail()).contains("39,300").contains("5,895").contains("2,500");
  }

  @Test
  void 没设预算时预算这条不判定() {
    HoldingReviewService.AddCheck c = service(null).addCheck("MSFT", 10);

    assertThat(c.held()).isFalse();
    assertThat(c.rules())
        .extracting(HoldingReviewService.Rule::status)
        .containsExactly("PASS", "PASS", "SKIPPED", "PASS");
    assertThat(c.allPass()).isTrue();
    assertThat(c.conclusion()).isEqualTo("满足你设定的加仓条件（有条件未判定）");
  }

  @Test
  void 券商还没排期时按季度节奏估计() {
    when(earnings.events(21L))
        .thenReturn(
            List.of(
                new EarningsEvent(
                    "2026/Q2",
                    2026,
                    LocalDate.of(2026, 7, 22),
                    null,
                    EarningsTiming.AFTER_MARKET,
                    false)));

    HoldingReviewService.Upcoming next = item(service(null).review(null), "GOOG").earnings().next();

    assertThat(next.estimated()).isTrue();
    assertThat(next.date()).isEqualTo(LocalDate.of(2026, 10, 21));
    assertThat(next.inWindow()).isTrue();
  }

  @Test
  void 审计提示只提示不判失败() {
    List<Check> checks = service(new BigDecimal("0.5")).auditChecks();

    Check ahead =
        checks.stream().filter(c -> c.name().equals("earningsAhead")).findFirst().orElseThrow();
    assertThat(ahead.ok()).isFalse();
    assertThat(ahead.critical()).isFalse();
    assertThat(ahead.samples())
        .singleElement()
        .asString()
        .contains("GOOG 2026-10-28 盘后（第 6 个交易日）")
        .contains("最大跌 10.0%")
        .contains("-3,645");
    Check review =
        checks.stream().filter(c -> c.name().equals("reviewTriggers")).findFirst().orElseThrow();
    assertThat(review.ok()).isFalse();
    assertThat(review.critical()).isFalse();
    assertThat(review.detail()).contains("不是买卖建议");
  }

  @Test
  void 逐日历史只返回请求区间() {
    List<HoldingReviewService.HistoryDay> h =
        service(null).history("GOOG", dates.get(290), dates.get(299));

    assertThat(h).hasSize(10);
    assertThat(h.getLast().state()).isEqualTo("BROKEN");
  }
}
