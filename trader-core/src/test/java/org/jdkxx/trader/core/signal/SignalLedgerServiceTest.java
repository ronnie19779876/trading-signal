package org.jdkxx.trader.core.signal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import org.jdkxx.trader.core.marketdata.InstrumentDirectory;
import org.jdkxx.trader.storage.marketdata.DailyBarRepository;
import org.jdkxx.trader.storage.marketdata.RehabFactorRepository;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;
import org.jdkxx.trader.storage.signal.EntrySignalRepository;
import org.jdkxx.trader.storage.signal.EntrySignalRow;
import org.jdkxx.trader.storage.signal.SignalTrackRepository;
import org.jdkxx.trader.storage.signal.SignalTrackRow;
import org.junit.jupiter.api.Test;

/**
 * The paper ledger only moves forward, never backward.
 *
 * <p>A back-fill (BACKFILL) passes in <b>the back-filled day</b>, while open ledger rows are
 * normally computed to the latest trading day. Before 3.1.1 they were recomputed unconditionally:
 * one back-fill for 09-17 rolled every open row back to 09-17 as a whole, wiping the holding
 * history from 09-18 to today and even closes that had already happened, and {@code updatedThrough}
 * went back too. And the 22:00 catch-up could take this path any night (found in the 2026-09-25
 * full project review).
 */
class SignalLedgerServiceTest {

  private final EntrySignalRepository signals = mock(EntrySignalRepository.class);
  private final SignalTrackRepository tracks = mock(SignalTrackRepository.class);
  private final InstrumentDirectory directory = mock(InstrumentDirectory.class);
  private final DailyBarRepository bars = mock(DailyBarRepository.class);
  private final RehabFactorRepository rehabs = mock(RehabFactorRepository.class);
  private final TradingDayRepository days = mock(TradingDayRepository.class);

  private SignalLedgerService service() {
    return new SignalLedgerService(signals, tracks, directory, bars, rehabs, days);
  }

  private static SignalTrackRow track(long signalId, LocalDate updatedThrough) {
    return new SignalTrackRow(
        signalId,
        "BASE",
        "OPEN",
        null,
        null,
        null,
        null,
        false,
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
        updatedThrough,
        null);
  }

  /**
   * The crux: when back-filling an earlier date, rows already computed to a later date must not be
   * touched at all.
   */
  @Test
  void 补跑到更早的日期时_已经算到更晚的条目不动() {
    when(tracks.unfinished())
        .thenReturn(
            List.of(
                track(1, LocalDate.of(2026, 9, 24)), // already computed to 09-24
                track(2, LocalDate.of(2026, 9, 24))));

    SignalLedgerService.Summary s = service().update(LocalDate.of(2026, 9, 17));

    assertThat(s.updated()).isZero();
    verify(tracks, never()).update(any());
    verify(signals, never()).find(org.mockito.ArgumentMatchers.anyLong());
    verify(bars, never()).find(any(), org.mockito.ArgumentMatchers.anyLong(), any(), any());
  }

  /**
   * Rows not yet computed to that day are recomputed as usual - a back-fill is still useful for
   * rows that lag behind.
   */
  @Test
  void 落后的条目照常重算() {
    when(tracks.unfinished())
        .thenReturn(
            List.of(
                track(1, LocalDate.of(2026, 9, 10)), // behind, compute
                track(2, LocalDate.of(2026, 9, 24)), // already updated, skip
                track(3, null))); // never computed, compute
    when(signals.find(org.mockito.ArgumentMatchers.anyLong()))
        .thenReturn(java.util.Optional.empty());

    service().update(LocalDate.of(2026, 9, 17));

    // signals.find throws NoSuchElement, caught and counted as failed; this only checks which rows
    // entered the loop
    verify(signals).find(1L);
    verify(signals).find(3L);
    verify(signals, never()).find(2L);
  }

  /**
   * Boundary: exactly the already-computed date is not recomputed either (no new data, recomputing
   * is just waste).
   */
  @Test
  void 正好等于已算到的日期不重算() {
    when(tracks.unfinished()).thenReturn(List.of(track(1, LocalDate.of(2026, 9, 17))));

    assertThat(service().update(LocalDate.of(2026, 9, 17)).updated()).isZero();
    verify(tracks, never()).update(any());
  }

  // ------------------------------------------------------------------ current price and unrealized
  // P&L (3.1.4)

  private static final org.jdkxx.trader.domain.Instrument X =
      org.jdkxx.trader.domain.Instrument.us("X");

  private static org.jdkxx.trader.domain.DailyBar bar(LocalDate d, double c) {
    return new org.jdkxx.trader.domain.DailyBar(
        X,
        d,
        java.math.BigDecimal.valueOf(c),
        java.math.BigDecimal.valueOf(c + 1),
        java.math.BigDecimal.valueOf(c - 1),
        java.math.BigDecimal.valueOf(c),
        null,
        1000,
        null,
        null,
        null,
        null,
        false);
  }

  private static EntrySignalRow sig(LocalDate day, double close) {
    return new EntrySignalRow(
        1,
        1,
        "X",
        day,
        "sentinel-v1",
        "UNIVERSE",
        "LIVE",
        java.math.BigDecimal.valueOf(close),
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
        null,
        null,
        null,
        null,
        "NEW",
        day.plusDays(3),
        null,
        null,
        null,
        null);
  }

  private static SignalTrackRow trackWithStop(String variant, double stop) {
    return new SignalTrackRow(
        1,
        variant,
        "OPEN",
        java.math.BigDecimal.valueOf(stop),
        null,
        null,
        null,
        false,
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
        null,
        null);
  }

  /**
   * Builds a price series: 100 on the judgement date, then moving to a close of lastClose at end.
   */
  private static Object[] series(LocalDate signalDay, LocalDate end, double lastClose) {
    List<org.jdkxx.trader.domain.DailyBar> raw = new java.util.ArrayList<>();
    java.util.Set<LocalDate> cal = new java.util.HashSet<>();
    for (LocalDate d = signalDay.minusDays(30); !d.isAfter(signalDay); d = d.plusDays(1)) {
      raw.add(bar(d, 100));
      cal.add(d);
    }
    for (LocalDate d = signalDay.plusDays(1); !d.isAfter(end); d = d.plusDays(1)) {
      raw.add(bar(d, d.equals(end) ? lastClose : 100));
      cal.add(d);
    }
    return new Object[] {raw, cal};
  }

  @Test
  @SuppressWarnings("unchecked")
  void 未平仓填当前价_已平仓三列留空() {
    LocalDate day = LocalDate.of(2026, 3, 2);
    LocalDate end = day.plusDays(3);

    Object[] openSeries = series(day, end, 104); // above the stop -> still open
    SignalTrackRow open =
        service()
            .recompute(
                sig(day, 100),
                trackWithStop("BASE", 95),
                (List<org.jdkxx.trader.domain.DailyBar>) openSeries[0],
                List.of(),
                (java.util.Set<LocalDate>) openSeries[1],
                end);

    assertThat(open.status()).isEqualTo("OPEN");
    assertThat(open.lastClose())
        .isNotNull()
        .satisfies(v -> assertThat(v.doubleValue()).isCloseTo(104, within(1e-6)));
    assertThat(open.lastCloseDate()).isEqualTo(end);
    assertThat(open.unrealizedR()).isNotNull();

    Object[] closedSeries = series(day, end, 90); // below the stop -> closed
    SignalTrackRow closed =
        service()
            .recompute(
                sig(day, 100),
                trackWithStop("BASE", 95),
                (List<org.jdkxx.trader.domain.DailyBar>) closedSeries[0],
                List.of(),
                (java.util.Set<LocalDate>) closedSeries[1],
                end);

    assertThat(closed.status()).isEqualTo("CLOSED");
    assertThat(closed.lastClose()).as("已平仓看 exit_price，当前价留空——不是 0").isNull();
    assertThat(closed.lastCloseDate()).isNull();
    assertThat(closed.unrealizedR()).as("已平仓看 r_multiple").isNull();
  }

  /**
   * Both variants necessarily have the same current price and unrealized return (same entry, same
   * current price), but <b>unrealized_r differs</b>: R = judgement-date close minus this variant's
   * stop, so different stops give different R. Production record 2026-10-09: AMAT's R was 43.88
   * (BASE) versus 54.10 (STOP_2_5).
   */
  @Test
  @SuppressWarnings("unchecked")
  void 两个变体当前价相同但浮动R不同() {
    LocalDate day = LocalDate.of(2026, 3, 2);
    LocalDate end = day.plusDays(3);
    Object[] s = series(day, end, 104);
    List<org.jdkxx.trader.domain.DailyBar> raw = (List<org.jdkxx.trader.domain.DailyBar>) s[0];
    java.util.Set<LocalDate> cal = (java.util.Set<LocalDate>) s[1];

    SignalTrackRow base =
        service().recompute(sig(day, 100), trackWithStop("BASE", 95), raw, List.of(), cal, end);
    SignalTrackRow wide =
        service()
            .recompute(sig(day, 100), trackWithStop("STOP_2_5", 93.75), raw, List.of(), cal, end);

    assertThat(base.lastClose()).isEqualByComparingTo(wide.lastClose());
    assertThat(base.lastCloseDate()).isEqualTo(wide.lastCloseDate());
    // entry 100, current 104 -> BASE: 4/5 = 0.8; STOP_2_5: 4/6.25 = 0.64
    assertThat(base.unrealizedR().doubleValue()).isCloseTo(0.8, within(1e-6));
    assertThat(wide.unrealizedR().doubleValue()).isCloseTo(0.64, within(1e-6));
    assertThat(base.unrealizedR()).isNotEqualByComparingTo(wide.unrealizedR());
  }
}
