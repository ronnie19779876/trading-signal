package org.jdkxx.trader.domain.signal;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Williams fractals and zone clustering (pure functions).
 *
 * <p><b>Freedom from look-ahead is guaranteed structurally</b>: a fractal needs {@code side} bars
 * on its right to be confirmed, so the scan stops at {@code n−1−side}; the last {@code side} bars
 * of the array can never be fractals, and the evaluation day's own high/low never enters a zone.
 * Backtests and live evaluation are therefore identical in shape.
 */
public final class Fractals {

  private Fractals() {}

  /**
   * Indexes of fractal lows: strictly lower than {@code side} bars on each side. Only indexes &gt;=
   * {@code from} are returned.
   */
  public static List<Integer> lows(double[] low, int side, int from) {
    return scan(low, side, from, true);
  }

  /**
   * Indexes of fractal highs: strictly higher than {@code side} bars on each side. Only indexes
   * &gt;= {@code from} are returned.
   */
  public static List<Integer> highs(double[] high, int side, int from) {
    return scan(high, side, from, false);
  }

  private static List<Integer> scan(double[] x, int side, int from, boolean lows) {
    List<Integer> out = new ArrayList<>();
    int last = x.length - 1 - side;
    for (int i = Math.max(side, from); i <= last; i++) {
      boolean fractal = true;
      for (int k = 1; k <= side && fractal; k++) {
        fractal = lows ? x[i] < x[i - k] && x[i] < x[i + k] : x[i] > x[i - k] && x[i] > x[i + k];
      }
      if (fractal) {
        out.add(i);
      }
    }
    return out;
  }

  /**
   * Single-linkage clustering after sorting by price: neighbours whose price difference is &lt;=
   * tolerance belong to the same cluster (so a zone can be wider than the tolerance by chaining),
   * and clusters with at least {@code minTouches} members are kept. The zones are returned in
   * ascending order of their bottom; ties in price are broken by index so the result is
   * deterministic.
   */
  public static List<PriceZone> cluster(
      List<Integer> indexes, double[] price, LocalDate[] dates, double tolerance, int minTouches) {
    List<Integer> sorted = new ArrayList<>(indexes);
    sorted.sort(Comparator.<Integer>comparingDouble(i -> price[i]).thenComparing(i -> i));
    List<PriceZone> zones = new ArrayList<>();
    List<Integer> current = new ArrayList<>();
    for (int idx : sorted) {
      if (!current.isEmpty() && price[idx] - price[current.get(current.size() - 1)] > tolerance) {
        addIfQualified(zones, current, price, dates, minTouches);
        current = new ArrayList<>();
      }
      current.add(idx);
    }
    addIfQualified(zones, current, price, dates, minTouches);
    return zones;
  }

  private static void addIfQualified(
      List<PriceZone> zones,
      List<Integer> members,
      double[] price,
      LocalDate[] dates,
      int minTouches) {
    if (members.size() < minTouches) {
      return;
    }
    List<LocalDate> memberDates = members.stream().sorted().map(i -> dates[i]).toList();
    zones.add(
        new PriceZone(
            price[members.get(0)],
            price[members.get(members.size() - 1)],
            members.size(),
            memberDates));
  }
}
