package org.jdkxx.trader.core.signal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.jdkxx.trader.core.marketdata.InstrumentDirectory;
import org.jdkxx.trader.core.marketdata.bars.SettledCutoff;
import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.SecurityType;
import org.jdkxx.trader.domain.signal.SignalSuppression;
import org.jdkxx.trader.storage.marketdata.DailyBarRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.jdkxx.trader.storage.marketdata.RehabFactorRepository;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;
import org.junit.jupiter.api.Test;

class SentinelServiceTest {

  private static final Instrument X = Instrument.us("X");

  private final List<DailyBar> series = new ArrayList<>();
  private final List<LocalDate> calendar = new ArrayList<>();

  private SentinelService service() {
    InstrumentDirectory directory = mock(InstrumentDirectory.class);
    when(directory.require(anyString()))
        .thenReturn(
            new InstrumentRow(
                1,
                Market.US,
                "X",
                "X",
                null,
                SecurityType.STOCK,
                1,
                null,
                false,
                null,
                null,
                "RESOLVED"));
    DailyBarRepository bars = mock(DailyBarRepository.class);
    when(bars.find(any(), anyLong(), any(), any()))
        .thenAnswer(
            inv -> {
              LocalDate from = inv.getArgument(2);
              LocalDate to = inv.getArgument(3);
              return series.stream()
                  .filter(b -> !b.tradeDate().isBefore(from) && !b.tradeDate().isAfter(to))
                  .toList();
            });
    RehabFactorRepository rehabs = mock(RehabFactorRepository.class);
    when(rehabs.find(any(), anyLong())).thenReturn(List.of());
    TradingDayRepository days = mock(TradingDayRepository.class);
    when(days.between(eq(Market.US), any(), any()))
        .thenAnswer(
            inv -> {
              LocalDate from = inv.getArgument(1);
              LocalDate to = inv.getArgument(2);
              return calendar.stream().filter(d -> !d.isBefore(from) && !d.isAfter(to)).toList();
            });
    SettledCutoff cutoff = mock(SettledCutoff.class);
    when(cutoff.current()).thenReturn(calendar.get(calendar.size() - 1));
    return new SentinelService(directory, bars, rehabs, days, cutoff);
  }

  /**
   * The synthetic series of {@code SignalEvaluationServiceTest}: a rally, then oscillation around
   * 155 forming a support zone; each bar listed in {@code pullbackIdx} pulls back into the zone on
   * high volume and closes up, which passes all four gates.
   */
  private void build(int n, int... pullbackIdx) {
    LocalDate d = LocalDate.of(2025, 6, 2);
    java.util.Set<Integer> pb = new java.util.HashSet<>();
    for (int i : pullbackIdx) {
      pb.add(i);
    }
    for (int i = 0; i < n; i++) {
      while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
        d = d.plusDays(1);
      }
      boolean last = pb.contains(i);
      double p = i < 200 ? 100 + 0.25 * i : 155 + 5 * Math.sin(2 * Math.PI * (i - 200) / 20.0);
      series.add(
          new DailyBar(
              X,
              d,
              bd(last ? 149.5 : p),
              bd(last ? 152.5 : p + 1),
              bd(last ? 148.5 : p - 1),
              bd(last ? 152 : p),
              null,
              last ? 3_000_000 : 1_000_000,
              null,
              null,
              null,
              null,
              false));
      calendar.add(d);
      d = d.plusDays(1);
    }
  }

  private static BigDecimal bd(double v) {
    return BigDecimal.valueOf(v);
  }

  /**
   * Guard: a replay is live-equivalent apart from the AI veto. Live judges the edge against the
   * previous trading day even when that day is outside anything "replayed"; before the 2026-10-31
   * review the replay started with "previous day not passed", so a second consecutive passing day
   * at the start of the range came out as a fresh SIGNAL.
   */
  @Test
  void 回放首日的边沿看区间前一交易日() {
    build(300, 298, 299);
    LocalDate start = calendar.get(299);

    SentinelService.Replay r = service().replay("X", start, start);

    assertThat(r.days()).hasSize(1);
    assertThat(r.days().get(0).gates()).isEqualTo("PPPP");
    assertThat(r.days().get(0).outcome()).isEqualTo(SignalSuppression.Outcome.SUPPRESSED_EDGE);
    assertThat(r.outcomeCounts()).containsExactly(java.util.Map.entry("SUPPRESSED_EDGE", 1L));
  }

  /**
   * Guard: the cooldown is warmed up on the trading days before the range (live reads the last
   * signal date from the table); signals found during the warm-up are not output and get no paper
   * trade.
   */
  @Test
  void 回放首日的冷却看区间之前的信号且预热期不出信号() {
    build(300, 295, 299);
    LocalDate start = calendar.get(299);

    SentinelService.Replay r = service().replay("X", start, start, true, null, false);

    assertThat(r.days()).hasSize(1);
    assertThat(r.days().get(0).gates()).isEqualTo("PPPP");
    assertThat(r.days().get(0).outcome()).isEqualTo(SignalSuppression.Outcome.SUPPRESSED_COOLDOWN);
    assertThat(r.statusCounts().values().stream().mapToLong(Long::longValue).sum()).isEqualTo(1);
    assertThat(r.trades()).isEmpty();
  }

  /** Control: the same series replayed from before the warm-up signal still emits it. */
  @Test
  void 区间覆盖到的信号照常输出() {
    build(300, 295, 299);

    SentinelService.Replay r = service().replay("X", calendar.get(290), calendar.get(299));

    assertThat(r.outcomeCounts())
        .containsEntry("SIGNAL", 1L)
        .containsEntry("SUPPRESSED_COOLDOWN", 1L);
    assertThat(r.days()).hasSize(10);
  }
}
