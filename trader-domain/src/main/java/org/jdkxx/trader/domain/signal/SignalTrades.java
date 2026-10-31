package org.jdkxx.trader.domain.signal;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.RehabFactor;

/**
 * Paper trade of one signal (pure functions): replay statistics and the paper tracking ledger share
 * this single entry point, which guarantees both compute the same thing.
 *
 * <ul>
 *   <li>The data range is fixed to 600 calendar days before the evaluation day up to {@code
 *       through}: the Wilder ATR used by the chandelier stop depends on its starting point, so the
 *       start follows only the signal and never other signals of the same instrument or the query
 *       range;
 *   <li>The whole range is converted to the structural basis as of {@code through} (so splits
 *       during the holding period stay continuous); after simulating, prices are converted back to
 *       the <b>evaluation-day basis</b> so they compare directly with the levels stored in
 *       entry_signal;
 *   <li>Bars outside the trading calendar are dropped (as in evaluation).
 * </ul>
 */
public final class SignalTrades {

  private SignalTrades() {}

  /**
   * @param evalClose evaluation-day close (evaluation-day basis = raw price)
   * @param stopAtEval stop (evaluation-day basis)
   * @return null when there is no bar after the evaluation day yet; throws IllegalStateException
   *     when the basis conversion fails
   */
  public static PaperTrade.Result simulate(
      List<DailyBar> raw,
      List<RehabFactor> factors,
      Set<LocalDate> tradingDays,
      LocalDate signalDate,
      double evalClose,
      double stopAtEval,
      LocalDate through,
      SentinelThresholds th,
      PaperTrade.Rules rules) {
    LocalDate from = signalDate.minusDays(th.windowCalendarDays());
    List<DailyBar> slice =
        raw.stream()
            .filter(
                b ->
                    !b.tradeDate().isBefore(from)
                        && !b.tradeDate().isAfter(through)
                        && tradingDays.contains(b.tradeDate()))
            .toList();
    StructuralAdjustment.Result adjusted = StructuralAdjustment.apply(slice, factors, through);
    if (!adjusted.ok()) {
      throw new IllegalStateException("纸面交易的价量口径换算失败：" + adjusted.problem());
    }
    List<SignalBar> series = adjusted.bars();
    int index = -1;
    for (int i = 0; i < series.size(); i++) {
      if (series.get(i).date().equals(signalDate)) {
        index = i;
        break;
      }
    }
    if (index < 0) {
      throw new IllegalStateException(signalDate + " 没有 K 线，无法模拟");
    }
    double[] high = series.stream().mapToDouble(SignalBar::high).toArray();
    double[] low = series.stream().mapToDouble(SignalBar::low).toArray();
    double[] close = series.stream().mapToDouble(SignalBar::close).toArray();
    double[] atr = Indicators.wilderAtr(high, low, close, th.atrPeriod());
    double scale = close[index] / evalClose;
    PaperTrade.Result r = PaperTrade.simulate(series, atr, index, stopAtEval * scale, rules);
    if (r == null) {
      return null;
    }
    // lastClose goes through the same conversion back as entry / stop / exit: the current price
    // must be on the same basis as entry_price, otherwise a split during the holding period puts
    // them on different scales (the easiest trap when this field was added). scale = close on the
    // through-basis ÷ raw close on the evaluation day, so dividing by it maps any through-basis
    // price back to the evaluation-day basis
    return new PaperTrade.Result(
        r.signalDate(),
        r.entryDate(),
        r.entry() / scale,
        r.stop() / scale,
        r.plusOneR() / scale,
        r.touchedPlusOneR(),
        r.exitDate(),
        r.exit() == null ? null : r.exit() / scale,
        r.reason(),
        r.r(),
        r.mfeR(),
        r.maeR(),
        r.barsHeld(),
        r.lastClose() / scale,
        r.lastCloseDate());
  }

  /**
   * Bars for charting: the whole range is converted to the structural basis as of {@code through}
   * (continuous across splits) and then rescaled as a whole to the price scale of {@code asOf}, so
   * that evaluation-day levels (stop, +1R, support zones) line up with the bars on the chart. When
   * {@code asOf} has no bar, the nearest earlier non-blank bar sets the scale; with no bar at all
   * an empty list is returned. Volume is rescaled with the same share-count ratio.
   */
  public static List<SignalBar> scaledTo(
      List<DailyBar> raw,
      List<RehabFactor> factors,
      Set<LocalDate> tradingDays,
      LocalDate through,
      LocalDate asOf) {
    List<DailyBar> slice =
        raw.stream()
            .filter(b -> !b.tradeDate().isAfter(through) && tradingDays.contains(b.tradeDate()))
            .toList();
    StructuralAdjustment.Result adjusted = StructuralAdjustment.apply(slice, factors, through);
    if (!adjusted.ok()) {
      throw new IllegalStateException("价量口径换算失败：" + adjusted.problem());
    }
    DailyBar anchorRaw = null;
    SignalBar anchorAdj = null;
    for (int i = 0; i < slice.size(); i++) {
      if (!slice.get(i).tradeDate().isAfter(asOf) && !slice.get(i).blank()) {
        anchorRaw = slice.get(i);
      }
    }
    if (anchorRaw == null) {
      return List.of();
    }
    for (SignalBar b : adjusted.bars()) {
      if (b.date().equals(anchorRaw.tradeDate())) {
        anchorAdj = b;
      }
    }
    double price = anchorRaw.close().doubleValue() / anchorAdj.close();
    double volume = anchorAdj.volume() == 0 ? 1 : anchorRaw.volume() / anchorAdj.volume();
    return adjusted.bars().stream()
        .map(
            b ->
                new SignalBar(
                    b.date(),
                    b.open() * price,
                    b.high() * price,
                    b.low() * price,
                    b.close() * price,
                    b.volume() * volume))
        .toList();
  }

  /**
   * Stop for a given ATR multiple (evaluation-day basis): min(close − m×ATR, zone bottom −
   * 0.5×ATR); without a hit zone only the ATR leg is used.
   */
  public static double stop(
      double close, double atr, Double zoneBottom, double atrMultiple, SentinelThresholds th) {
    double stop = close - atrMultiple * atr;
    return zoneBottom == null ? stop : Math.min(stop, zoneBottom - th.zoneStopAtrMultiple() * atr);
  }
}
