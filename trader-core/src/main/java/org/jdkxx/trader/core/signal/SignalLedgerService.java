package org.jdkxx.trader.core.signal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jdkxx.trader.core.marketdata.InstrumentDirectory;
import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.RehabFactor;
import org.jdkxx.trader.domain.signal.PaperTrade;
import org.jdkxx.trader.domain.signal.SentinelThresholds;
import org.jdkxx.trader.domain.signal.SignalTrades;
import org.jdkxx.trader.storage.marketdata.DailyBarRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.jdkxx.trader.storage.marketdata.RehabFactorRepository;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;
import org.jdkxx.trader.storage.signal.EntrySignalRepository;
import org.jdkxx.trader.storage.signal.EntrySignalRow;
import org.jdkxx.trader.storage.signal.SignalTrackRepository;
import org.jdkxx.trader.storage.signal.SignalTrackRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Paper tracking ledger: every unfinished row (pending entry included) is <b>recomputed end to
 * end</b> each day from the signal date up to the cutoff ({@link SignalTrades}, the same entry
 * point as replay). No incremental state is stored: splits during the holding period and bar
 * corrections show up naturally in the recompute. Closed rows are not recomputed again.
 */
public class SignalLedgerService {

  private static final Logger log = LoggerFactory.getLogger(SignalLedgerService.class);

  public record Summary(int updated, int closed, int failed) {}

  private final EntrySignalRepository signals;
  private final SignalTrackRepository tracks;
  private final InstrumentDirectory directory;
  private final DailyBarRepository bars;
  private final RehabFactorRepository rehabs;
  private final TradingDayRepository days;
  private final SentinelThresholds th = SentinelThresholds.V1;

  public SignalLedgerService(
      EntrySignalRepository signals,
      SignalTrackRepository tracks,
      InstrumentDirectory directory,
      DailyBarRepository bars,
      RehabFactorRepository rehabs,
      TradingDayRepository days) {
    this.signals = signals;
    this.tracks = tracks;
    this.directory = directory;
    this.bars = bars;
    this.rehabs = rehabs;
    this.days = days;
  }

  /**
   * Brings the open paper ledger up to {@code through}.
   *
   * <p><b>Forward only, never backward</b> (fixed in 3.1.2): rows already computed to a later date
   * are skipped. A back-fill (BACKFILL) passes in <b>the back-filled day</b>, while open ledger
   * rows are normally computed to the latest trading day - previously they were recomputed
   * unconditionally, so one back-fill for 09-17 rolled every open row back to 09-17 as a whole,
   * wiping the holding history from 09-18 to today and even closes that had already happened
   * ({@code updatedThrough} went back as well); and the 22:00 catch-up could take this path any
   * night (found in the 2026-09-25 full project review).
   */
  public Summary update(LocalDate through) {
    Map<Long, List<SignalTrackRow>> bySignal =
        tracks.unfinished().stream()
            .filter(t -> t.updatedThrough() == null || t.updatedThrough().isBefore(through))
            .collect(
                Collectors.groupingBy(
                    SignalTrackRow::signalId, LinkedHashMap::new, Collectors.toList()));
    int updated = 0;
    int closed = 0;
    int failed = 0;
    for (Map.Entry<Long, List<SignalTrackRow>> en : bySignal.entrySet()) {
      try {
        EntrySignalRow signal = signals.find(en.getKey()).orElseThrow();
        if (signal.tradeDate().isAfter(through)) {
          continue;
        }
        InstrumentRow row = directory.require(signal.instrumentId());
        // Same look-back window as the evaluation, so the ATR / chandelier inputs match the signal
        // day exactly
        LocalDate from = signal.tradeDate().minusDays(th.windowCalendarDays());
        List<DailyBar> raw = bars.find(row.instrument(), row.id(), from, through);
        List<RehabFactor> factors = rehabs.find(row.instrument(), row.id());
        Set<LocalDate> calendar = new HashSet<>(days.between(Market.US, from, through));
        for (SignalTrackRow t : en.getValue()) {
          SignalTrackRow next = recompute(signal, t, raw, factors, calendar, through);
          tracks.update(next);
          updated++;
          closed += "CLOSED".equals(next.status()) ? 1 : 0;
        }
      } catch (RuntimeException e) {
        failed++;
        log.warn("信号 #{} 账本重算失败：{}", en.getKey(), e.toString());
      }
    }
    return new Summary(updated, closed, failed);
  }

  /**
   * Recomputes one variant row. Prices come back on the signal-date basis (SignalTrades scales them
   * back), so entry, stop, exit and current price can be subtracted directly even across a split.
   */
  SignalTrackRow recompute(
      EntrySignalRow signal,
      SignalTrackRow t,
      List<DailyBar> raw,
      List<RehabFactor> factors,
      Set<LocalDate> calendar,
      LocalDate through) {
    PaperTrade.Result r =
        SignalTrades.simulate(
            raw,
            factors,
            calendar,
            signal.tradeDate(),
            signal.close().doubleValue(),
            t.stop().doubleValue(),
            through,
            th,
            PaperTrade.Rules.of(th));
    if (r == null) {
      return new SignalTrackRow(
          t.signalId(),
          t.variant(),
          "PENDING_ENTRY",
          t.stop(),
          t.plusOneR(),
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
          null, // current price / date / unrealized R: not entered yet, no cost to compare against
          null,
          through,
          null);
    }
    boolean open = r.reason() == PaperTrade.ExitReason.OPEN;
    // Current price is only filled for open rows: a pending entry has no cost, a closed row uses
    // exit_price. lastClose has already been scaled back to the judgement-date basis in
    // SignalTrades, the same scale as entryPrice, so the two can be subtracted directly.
    BigDecimal lastClose = open ? d(r.lastClose()) : null;
    LocalDate lastCloseDate = open ? r.lastCloseDate() : null;
    BigDecimal unrealizedR = open ? unrealized(r.lastClose(), r.entry(), signal, t) : null;
    return new SignalTrackRow(
        t.signalId(),
        t.variant(),
        open ? "OPEN" : "CLOSED",
        t.stop(),
        t.plusOneR(),
        r.entryDate(),
        d(r.entry()),
        r.touchedPlusOneR(),
        r.exitDate(),
        r.exit() == null ? null : d(r.exit()),
        open ? null : r.reason().name(),
        r.r() == null ? null : d(r.r()),
        r.exit() == null ? null : d((r.exit() - r.entry()) / r.entry()),
        d(r.mfeR()),
        d(r.maeR()),
        lastClose,
        lastCloseDate,
        unrealizedR,
        r.barsHeld(),
        through,
        null);
  }

  /**
   * Unrealized P&amp;L divided by R. R = judgement-date close minus <b>this variant's</b> stop, so
   * BASE and STOP_2_5 usually have different R (stop = min(close - m x ATR, zone bottom - 0.5 x
   * ATR); only when the zone-bottom leg applies is it independent of the ATR multiple and the two
   * variants match). The unrealized return, by contrast, is necessarily the same for both variants
   * - same entry, same current price. Returns null when the risk is not positive (avoids dividing
   * by zero or a negative R).
   */
  private static BigDecimal unrealized(
      double lastClose, double entry, EntrySignalRow signal, SignalTrackRow t) {
    double risk = signal.close().doubleValue() - t.stop().doubleValue();
    return risk > 0 ? d((lastClose - entry) / risk) : null;
  }

  private static BigDecimal d(double v) {
    return BigDecimal.valueOf(v).setScale(6, RoundingMode.HALF_UP);
  }
}
