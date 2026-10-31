package org.jdkxx.trader.domain.signal;

import java.time.LocalDate;
import java.util.List;

/**
 * Paper trade simulation (pure function): walks one signal day by day through its exit plan until
 * it exits. Shared by the paper tracking ledger and the replay statistics.
 *
 * <p>Modelling assumptions (aligned with the futu-trader backtest A1~A8; differences are called
 * out):
 *
 * <ol>
 *   <li>Entry: the signal is produced after the evaluation day's close and fills at the <b>next
 *       day's open</b>;
 *   <li>R = evaluation-day close − stop, +1R = evaluation-day close + R; both are absolute price
 *       levels and do not drift with the entry price;
 *   <li>The stop is judged on the <b>close</b> (close &lt;= stop) and fills at that day's close;
 *   <li>The chandelier stop is tracked daily as 22-day high − 3×ATR14 (of that day); it only takes
 *       effect <b>after +1R has been touched intraday</b> (from that same bar on), and the higher
 *       of it and the initial stop applies. The 22-day high includes days before entry (the same
 *       convention as the simulated trading);
 *   <li>Time stop: the entry day is day 0; if +1R has never been touched by the close of trading
 *       day 20, exit;
 *   <li>The chandelier ATR uses the Wilder recursion over the whole series (futu-trader recomputes
 *       it per day over a 600-day window; the difference is very small);
 *   <li>Halving (only in the comparison variant): at the first intraday touch of +1R half fills at
 *       +1R, or at the open when the bar gaps up above +1R (futu-trader always uses +1R);
 *   <li>No commissions, slippage or financing; no adding to the position; a trade still open at the
 *       end of the sample is recorded as open and excluded from R statistics (its unrealized gain
 *       counts as zero).
 * </ol>
 *
 * The price series must be on one basis (the whole series converted relative to one evaluation-day
 * base); the caller converts the stop and +1R to that basis.
 */
public final class PaperTrade {

  private PaperTrade() {}

  public record Rules(
      boolean halfAtPlusOneR,
      int chandelierPeriod,
      double chandelierAtrMultiple,
      int timeStopDays) {
    public static Rules of(SentinelThresholds th) {
      return new Rules(false, th.chandelierPeriod(), th.chandelierAtrMultiple(), th.timeStopDays());
    }

    public Rules withHalfAtPlusOneR() {
      return new Rules(true, chandelierPeriod, chandelierAtrMultiple, timeStopDays);
    }
  }

  public enum ExitReason {
    STOP,
    CHANDELIER,
    TIME,
    OPEN
  }

  /**
   * @param r realized P&amp;L ÷ R (weighted over the two halves when halving); null while open
   * @param mfeR maximum favourable excursion during the holding period ÷ R (from intraday highs): a
   *     level that was <b>reached at some point</b>, not the current level
   * @param maeR maximum adverse excursion during the holding period ÷ R (from intraday lows,
   *     negative)
   * @param lastClose close of the <b>last bar</b> the simulation reached: the current price while
   *     open, equal to {@code exit} once closed
   * @param lastCloseDate date of that bar. It <b>may be earlier than the requested end date</b>: it
   *     lags when the instrument is suspended or bars are missing (as with WBD in 2026-10), so a
   *     displayed current price must always come with this date, or a stale price looks like
   *     today's
   */
  public record Result(
      LocalDate signalDate,
      LocalDate entryDate,
      double entry,
      double stop,
      double plusOneR,
      boolean touchedPlusOneR,
      LocalDate exitDate,
      Double exit,
      ExitReason reason,
      Double r,
      double mfeR,
      double maeR,
      int barsHeld,
      double lastClose,
      LocalDate lastCloseDate) {}

  /**
   * @param bars daily bars on one basis, ascending
   * @param atr ATR14 aligned with {@code bars}
   * @param signal index of the evaluation day
   * @return null when there is no bar after the evaluation day yet (entry not possible yet)
   */
  public static Result simulate(
      List<SignalBar> bars, double[] atr, int signal, double stop, Rules rules) {
    if (signal + 1 >= bars.size()) {
      return null;
    }
    double close = bars.get(signal).close();
    double risk = close - stop;
    if (!(risk > 0)) {
      throw new IllegalArgumentException("止损必须低于判定日收盘");
    }
    double target = close + risk;
    int entryIndex = signal + 1;
    double entry = bars.get(entryIndex).open();
    boolean touched = false;
    double halfExit = Double.NaN;
    double highest = Double.NEGATIVE_INFINITY;
    double lowest = Double.POSITIVE_INFINITY;
    for (int i = entryIndex; i < bars.size(); i++) {
      SignalBar b = bars.get(i);
      highest = Math.max(highest, b.high());
      lowest = Math.min(lowest, b.low());
      if (!touched && b.high() >= target) {
        touched = true;
        if (rules.halfAtPlusOneR()) {
          halfExit = Math.max(target, b.open()); // a gap open above +1R fills at the open
        }
      }
      double activeStop = stop;
      ExitReason stopReason = ExitReason.STOP;
      if (touched && !Double.isNaN(atr[i])) {
        double hh = Double.NEGATIVE_INFINITY;
        for (int k = Math.max(0, i - rules.chandelierPeriod() + 1); k <= i; k++) {
          hh = Math.max(hh, bars.get(k).high());
        }
        double chandelier = hh - rules.chandelierAtrMultiple() * atr[i];
        if (chandelier > activeStop) {
          activeStop = chandelier;
          stopReason = ExitReason.CHANDELIER;
        }
      }
      ExitReason reason = null;
      if (b.close() <= activeStop) {
        reason = stopReason;
      } else if (!touched && i - entryIndex >= rules.timeStopDays()) {
        reason = ExitReason.TIME;
      }
      if (reason != null) {
        double pnl =
            Double.isNaN(halfExit)
                ? b.close() - entry
                : 0.5 * (halfExit - entry) + 0.5 * (b.close() - entry);
        return new Result(
            bars.get(signal).date(),
            bars.get(entryIndex).date(),
            entry,
            stop,
            target,
            touched,
            b.date(),
            b.close(),
            reason,
            pnl / risk,
            (highest - entry) / risk,
            (lowest - entry) / risk,
            i - entryIndex + 1,
            b.close(),
            b.date());
      }
    }
    SignalBar last = bars.get(bars.size() - 1);
    return new Result(
        bars.get(signal).date(),
        bars.get(entryIndex).date(),
        entry,
        stop,
        target,
        touched,
        null,
        null,
        ExitReason.OPEN,
        null,
        (highest - entry) / risk,
        (lowest - entry) / risk,
        bars.size() - entryIndex,
        last.close(),
        last.date());
  }
}
