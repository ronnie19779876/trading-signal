package org.jdkxx.trader.domain.selection.sim;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import org.jdkxx.trader.domain.signal.Indicators;
import org.jdkxx.trader.domain.signal.SignalBar;

/**
 * Walks one simulated trade from the scoring day to the end date (pure function). It is always
 * <b>recomputed from scratch</b> from the levels fixed when the trade was added, with no
 * incremental state, so splits during the holding period (the caller converts to the structural
 * basis as of the end date and back to the scoring-day basis) and bar corrections are reflected
 * naturally.
 *
 * <p>Order within one bar: <b>open</b> (entry, manual exit), then intraday (whether +1R and the
 * take-profit target were touched, judged on the high), then <b>close</b> (stop check). A manual
 * exit only takes effect on bars after the entry bar; the stop is judged on the close and fills at
 * the close, so a gap through the stop records the larger loss as it happened instead of pretending
 * to fill at the stop. Take-profit only records the day it was touched and never closes the trade
 * automatically.
 *
 * <p>Unlike {@link org.jdkxx.trader.domain.signal.PaperTrade}, R here is measured from the actual
 * entry price (entry − stop), and there is no time stop.
 */
public final class SimPath {

  private SimPath() {}

  public enum State {
    /** The entry day's bar does not exist yet */
    PENDING,
    OPEN,
    CLOSED,
    /** The entry open is already at or below the stop */
    CANCELLED
  }

  public enum ExitReason {
    /** Close &lt;= initial stop */
    STOP,
    /** Close &lt;= chandelier stop (which has already been raised above the initial stop) */
    CHANDELIER,
    /** Closed at the open on request (the caller further splits this into take-profit / manual) */
    REQUESTED
  }

  public enum TargetSource {
    RESISTANCE,
    FALLBACK_2R
  }

  /**
   * All prices are on the scoring-day basis.
   *
   * @param highest highest (intraday) price since entry, for maximum favourable excursion
   * @param lowest lowest (intraday) price since entry, for maximum adverse excursion
   * @param closes close of every trading day in the holding period (from the entry day; excluding a
   *     day closed at the open), for the equity curve
   */
  public record Path(
      State state,
      LocalDate entryDate,
      Double entry,
      Double plusOneR,
      Double target,
      TargetSource targetSource,
      boolean touchedPlusOneR,
      LocalDate targetHitDate,
      Double activeStop,
      boolean chandelierActive,
      LocalDate exitDate,
      Double exit,
      ExitReason reason,
      Double highest,
      Double lowest,
      int barsHeld,
      Double lastClose,
      LocalDate lastCloseDate,
      NavigableMap<LocalDate, Double> closes,
      String cancelReason) {

    /** Risk per share R = entry − stop. */
    public Double risk(double stop) {
      return entry == null ? null : entry - stop;
    }
  }

  /**
   * @param bars daily bars on the scoring-day basis, ascending, must include the scoring day; keep
   *     enough history before the scoring day for the ATR
   * @param plannedEntry earliest entry date: entry at the open of the first bar after the scoring
   *     day dated on or after it
   * @param stop initial stop
   * @param resistance bottom of the nearest resistance zone above, null if none
   * @param plannedExit earliest manual exit date, null without a request: exit at the open of the
   *     first bar after the entry bar dated on or after it
   */
  public static Path walk(
      List<SignalBar> bars,
      LocalDate setupDate,
      LocalDate plannedEntry,
      double stop,
      Double resistance,
      LocalDate plannedExit,
      SimRules rules) {
    int n = bars.size();
    int setup = -1;
    for (int i = 0; i < n; i++) {
      if (i > 0 && !bars.get(i).date().isAfter(bars.get(i - 1).date())) {
        throw new IllegalArgumentException("K 线必须按日期严格升序：" + bars.get(i).date());
      }
      if (bars.get(i).date().equals(setupDate)) {
        setup = i;
      }
    }
    if (setup < 0) {
      throw new IllegalArgumentException(setupDate + " 没有 K 线");
    }
    int entryIdx = -1;
    for (int i = setup + 1; i < n; i++) {
      if (!bars.get(i).date().isBefore(plannedEntry)) {
        entryIdx = i;
        break;
      }
    }
    if (entryIdx < 0) {
      return new Path(
          State.PENDING,
          null,
          null,
          null,
          null,
          null,
          false,
          null,
          null,
          false,
          null,
          null,
          null,
          null,
          null,
          0,
          null,
          null,
          Collections.emptyNavigableMap(),
          null);
    }
    SignalBar first = bars.get(entryIdx);
    double entry = first.open();
    if (entry <= stop) {
      return new Path(
          State.CANCELLED,
          first.date(),
          entry,
          null,
          null,
          null,
          false,
          null,
          null,
          false,
          null,
          null,
          null,
          null,
          null,
          0,
          null,
          null,
          Collections.emptyNavigableMap(),
          "开盘 " + fmt(entry) + " 已不高于止损 " + fmt(stop) + "，不入场");
    }
    double risk = entry - stop;
    double plusOneR = entry + risk;
    boolean useResistance = resistance != null && resistance > entry;
    double target = useResistance ? resistance : entry + rules.targetFallbackR() * risk;
    TargetSource source = useResistance ? TargetSource.RESISTANCE : TargetSource.FALLBACK_2R;

    double[] high = bars.stream().mapToDouble(SignalBar::high).toArray();
    double[] low = bars.stream().mapToDouble(SignalBar::low).toArray();
    double[] close = bars.stream().mapToDouble(SignalBar::close).toArray();
    double[] atr = Indicators.wilderAtr(high, low, close, rules.sentinel().atrPeriod());

    NavigableMap<LocalDate, Double> closes = new TreeMap<>();
    boolean touched = false;
    LocalDate targetHit = null;
    double highest = Double.NEGATIVE_INFINITY;
    double lowest = Double.POSITIVE_INFINITY;
    double activeStop = stop;
    boolean chandelierActive = false;
    for (int i = entryIdx; i < n; i++) {
      SignalBar b = bars.get(i);
      if (i > entryIdx && plannedExit != null && !b.date().isBefore(plannedExit)) {
        return closed(
            entry,
            plusOneR,
            target,
            source,
            touched,
            targetHit,
            activeStop,
            chandelierActive,
            bars.get(entryIdx).date(),
            b.date(),
            b.open(),
            ExitReason.REQUESTED,
            highest,
            lowest,
            i - entryIdx,
            closes);
      }
      highest = Math.max(highest, b.high());
      lowest = Math.min(lowest, b.low());
      if (!touched && b.high() >= plusOneR) {
        touched = true;
      }
      if (targetHit == null && b.high() >= target) {
        targetHit = b.date();
      }
      if (touched && !Double.isNaN(atr[i])) {
        double hh = Double.NEGATIVE_INFINITY;
        for (int k = Math.max(0, i - rules.sentinel().chandelierPeriod() + 1); k <= i; k++) {
          hh = Math.max(hh, high[k]);
        }
        double chandelier = hh - rules.sentinel().chandelierAtrMultiple() * atr[i];
        if (chandelier > activeStop) {
          activeStop = chandelier; // only ever raised
          chandelierActive = true;
        }
      }
      if (b.close() <= activeStop) {
        closes.put(b.date(), b.close());
        return closed(
            entry,
            plusOneR,
            target,
            source,
            touched,
            targetHit,
            activeStop,
            chandelierActive,
            bars.get(entryIdx).date(),
            b.date(),
            b.close(),
            chandelierActive ? ExitReason.CHANDELIER : ExitReason.STOP,
            highest,
            lowest,
            i - entryIdx + 1,
            closes);
      }
      closes.put(b.date(), b.close());
    }
    SignalBar last = bars.get(n - 1);
    return new Path(
        State.OPEN,
        bars.get(entryIdx).date(),
        entry,
        plusOneR,
        target,
        source,
        touched,
        targetHit,
        activeStop,
        chandelierActive,
        null,
        null,
        null,
        highest,
        lowest,
        n - entryIdx,
        last.close(),
        last.date(),
        Collections.unmodifiableNavigableMap(closes),
        null);
  }

  private static Path closed(
      double entry,
      double plusOneR,
      double target,
      TargetSource source,
      boolean touched,
      LocalDate targetHit,
      double activeStop,
      boolean chandelierActive,
      LocalDate entryDate,
      LocalDate exitDate,
      double exit,
      ExitReason reason,
      double highest,
      double lowest,
      int barsHeld,
      NavigableMap<LocalDate, Double> closes) {
    // When closing at the open, the exit price is also a price that really occurred during the
    // holding period, so it counts towards the highest / lowest (a trade closed at the open the day
    // after entry only has the entry price and this one)
    double hi = Math.max(Double.isInfinite(highest) ? entry : highest, exit);
    double lo = Math.min(Double.isInfinite(lowest) ? entry : lowest, exit);
    return new Path(
        State.CLOSED,
        entryDate,
        entry,
        plusOneR,
        target,
        source,
        touched,
        targetHit,
        activeStop,
        chandelierActive,
        exitDate,
        exit,
        reason,
        hi,
        lo,
        barsHeld,
        exit,
        exitDate,
        Collections.unmodifiableNavigableMap(closes),
        null);
  }

  private static String fmt(double x) {
    return java.math.BigDecimal.valueOf(x)
        .setScale(4, java.math.RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString();
  }
}
