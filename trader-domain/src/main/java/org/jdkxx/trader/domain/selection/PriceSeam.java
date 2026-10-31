package org.jdkxx.trader.domain.selection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Seam check between IBKR daily bars and Futu daily bars (quant selection, ARCHITECTURE §25.6).
 *
 * <p>The two sides use different bases: IBKR is split-adjusted and Futu unadjusted, so their price
 * levels differ by a constant factor; therefore <b>daily returns are compared, not prices</b>. Only
 * consecutive days on which both sides have a close are compared (days missing on either side
 * cannot misalign the intervals); a pair whose interval contains a share-count event (split,
 * consolidation, bonus / capitalization issue, spin-off) is skipped, because on the ex-date one
 * side is adjusted and the other is not, so the returns cannot match. Dividends are adjusted on
 * neither side, so they need no skipping.
 *
 * <p>This class is an independent implementation for quant selection; it and the entry signal's
 * structural basis ({@code domain.signal}) do not reference each other.
 */
public final class PriceSeam {

  /**
   * @param minCompared minimum number of compared pairs before concluding anything (must be
   *     positive: with zero pairs the detail text would dereference a null maximum)
   * @param tolerance tolerance for the difference in daily return (0.001 = 0.1%)
   * @param minShare minimum share of pairs within tolerance
   */
  public record Thresholds(int minCompared, double tolerance, double minShare) {
    public static final Thresholds DEFAULT = new Thresholds(60, 0.001, 0.95);
  }

  /**
   * @param overlapDays number of days on which both sides have a close
   * @param comparedDays number of consecutive-day pairs actually compared (after skipping
   *     share-count events)
   * @param withinDays number of pairs whose return difference is within tolerance
   * @param maxAbsDiff largest single-day return difference (as a ratio), null when no pair could be
   *     compared
   */
  public record Result(
      int overlapDays,
      int comparedDays,
      int withinDays,
      BigDecimal maxAbsDiff,
      boolean ok,
      String detail) {}

  private PriceSeam() {}

  /**
   * @param ibkr IBKR split-adjusted daily bars
   * @param futuCloses Futu unadjusted closes (only bars with trades)
   * @param shareChangeDates ex-dates of share-count events; a pair (prev, d] containing one is
   *     skipped
   */
  public static Result check(
      List<SplitAdjustedBar> ibkr,
      Map<LocalDate, BigDecimal> futuCloses,
      Set<LocalDate> shareChangeDates,
      Thresholds t) {
    NavigableSet<LocalDate> events = new TreeSet<>(shareChangeDates);
    TreeSet<LocalDate> common = new TreeSet<>();
    Map<LocalDate, BigDecimal> ib = new java.util.HashMap<>();
    for (SplitAdjustedBar b : ibkr) {
      ib.put(b.tradeDate(), b.close());
      if (futuCloses.containsKey(b.tradeDate())) {
        common.add(b.tradeDate());
      }
    }
    int compared = 0;
    int within = 0;
    int skipped = 0;
    double max = -1;
    LocalDate prev = null;
    for (LocalDate d : common) {
      if (prev != null) {
        LocalDate firstEvent = events.higher(prev);
        if (firstEvent != null && !firstEvent.isAfter(d)) {
          skipped++;
        } else {
          double rIb = ratio(ib.get(d), ib.get(prev));
          double rFu = ratio(futuCloses.get(d), futuCloses.get(prev));
          if (Double.isFinite(rIb) && Double.isFinite(rFu)) {
            double diff = Math.abs(rIb - rFu);
            compared++;
            if (diff < t.tolerance()) {
              within++;
            }
            max = Math.max(max, diff);
          }
        }
      }
      prev = d;
    }
    BigDecimal maxDiff = max < 0 ? null : BigDecimal.valueOf(max).setScale(6, RoundingMode.HALF_UP);
    boolean ok = compared >= t.minCompared() && within >= Math.ceil(t.minShare() * compared);
    String detail;
    if (compared < t.minCompared()) {
      detail = "可比较 " + compared + " 对，不足 " + t.minCompared() + " 对（重叠 " + common.size() + " 天）";
    } else {
      detail =
          "达标 "
              + within
              + "/"
              + compared
              + " 对（容忍 "
              + t.tolerance() * 100
              + "%），最大收益差 "
              + maxDiff.movePointRight(2).stripTrailingZeros().toPlainString()
              + "%";
    }
    if (skipped > 0) {
      detail += "；跳过股数变动 " + skipped + " 对";
    }
    return new Result(common.size(), compared, within, maxDiff, ok, detail);
  }

  private static double ratio(BigDecimal cur, BigDecimal prev) {
    if (cur == null || prev == null || prev.signum() <= 0) {
      return Double.NaN;
    }
    return cur.doubleValue() / prev.doubleValue() - 1;
  }
}
