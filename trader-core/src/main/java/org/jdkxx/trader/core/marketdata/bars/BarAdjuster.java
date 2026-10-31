package org.jdkxx.trader.core.marketdata.bars;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jdkxx.trader.domain.Adjustment;
import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.RehabFactor;

/**
 * Read-side price adjustment (pure function): price = unadjusted price × A + B; volume is
 * unchanged. Forward adjustment applies events with exDate &gt; trade date; backward adjustment
 * applies events with exDate ≤ trade date.
 *
 * <p>Futu's factors are per-event ratios, not cumulative (see {@link FactorMode}): forward
 * adjustment composes them from earliest to latest, backward adjustment from latest to earliest. Do
 * not change the composition direction. Results are rounded to 6 decimals (HALF_UP) with trailing
 * zeros stripped.
 */
public final class BarAdjuster {

  private static final int SCALE = 6;
  private static final BigDecimal[] IDENTITY = {BigDecimal.ONE, BigDecimal.ZERO};

  private BarAdjuster() {}

  public static List<DailyBar> adjust(
      List<DailyBar> bars, List<RehabFactor> factors, Adjustment adjustment, FactorMode mode) {
    if (adjustment == Adjustment.NONE || factors.isEmpty() || bars.isEmpty()) {
      return bars;
    }
    List<RehabFactor> sorted = new ArrayList<>(factors);
    sorted.sort(Comparator.comparing(RehabFactor::exDate));
    List<DailyBar> out = new ArrayList<>(bars.size());
    for (DailyBar b : bars) {
      BigDecimal[] ab =
          adjustment == Adjustment.FORWARD ? forward(sorted, b, mode) : backward(sorted, b, mode);
      // The previous close belongs to the previous trading day, so it is converted with that day's
      // event set (the previous close of the bar on an ex-date must also be adjusted by that day's
      // event). "Previous day" is taken as the calendar day before, which selects the same events
      // as long as ex-dates fall on trading days
      DailyBar prev = b.withPrices(b.open(), b.high(), b.low(), b.close(), b.lastClose());
      DailyBar prevDay =
          new DailyBar(
              b.instrument(),
              b.tradeDate().minusDays(1),
              b.open(),
              b.high(),
              b.low(),
              b.close(),
              b.lastClose(),
              b.volume(),
              b.turnover(),
              b.turnoverRate(),
              b.changeRate(),
              b.pe(),
              b.blank());
      BigDecimal[] abPrev =
          adjustment == Adjustment.FORWARD
              ? forward(sorted, prevDay, mode)
              : backward(sorted, prevDay, mode);
      if (ab == null && abPrev == null) {
        out.add(b);
        continue;
      }
      BigDecimal[] a1 = ab == null ? IDENTITY : ab;
      BigDecimal[] a0 = abPrev == null ? IDENTITY : abPrev;
      out.add(
          prev.withPrices(
              apply(b.open(), a1),
              apply(b.high(), a1),
              apply(b.low(), a1),
              apply(b.close(), a1),
              b.lastClose() == null ? null : apply(b.lastClose(), a0)));
    }
    return out;
  }

  /** Returns {A, B}; null when no event applies. */
  static BigDecimal[] forward(List<RehabFactor> asc, DailyBar bar, FactorMode mode) {
    BigDecimal a = null;
    BigDecimal bb = null;
    for (RehabFactor f : asc) {
      if (!f.exDate().isAfter(bar.tradeDate())) {
        continue;
      }
      if (mode == FactorMode.CUMULATIVE) {
        return new BigDecimal[] {f.fwdA(), f.fwdB()};
      }
      if (a == null) {
        a = f.fwdA();
        bb = f.fwdB();
      } else {
        // Apply the earlier event first, then the later one: p' = A2 (A1 p + B1) + B2
        bb = f.fwdA().multiply(bb).add(f.fwdB());
        a = f.fwdA().multiply(a);
      }
    }
    return a == null ? null : new BigDecimal[] {a, bb};
  }

  /**
   * Backward adjustment: applies events with exDate ≤ trade date. Per-event mode composes <b>from
   * latest to earliest</b>: p' = A_earliest(… (A_latest p + B_latest) …) + B_earliest, because a
   * later dividend has to be scaled up by the earlier split ratios (measured: ascending composition
   * differs from Futu's backward-adjusted series by 2.5%; only descending matches).
   */
  static BigDecimal[] backward(List<RehabFactor> asc, DailyBar bar, FactorMode mode) {
    List<RehabFactor> applicable = new ArrayList<>();
    for (RehabFactor f : asc) {
      if (f.exDate().isAfter(bar.tradeDate())) {
        break;
      }
      applicable.add(f);
    }
    if (applicable.isEmpty()) {
      return null;
    }
    if (mode == FactorMode.CUMULATIVE) {
      RehabFactor last = applicable.get(applicable.size() - 1);
      return new BigDecimal[] {last.bwdA(), last.bwdB()};
    }
    BigDecimal a = null;
    BigDecimal bb = null;
    for (int i = applicable.size() - 1; i >= 0; i--) {
      RehabFactor f = applicable.get(i);
      if (a == null) {
        a = f.bwdA();
        bb = f.bwdB();
      } else {
        bb = f.bwdA().multiply(bb).add(f.bwdB());
        a = f.bwdA().multiply(a);
      }
    }
    return new BigDecimal[] {a, bb};
  }

  private static BigDecimal apply(BigDecimal price, BigDecimal[] ab) {
    if (price == null) {
      return null;
    }
    return price
        .multiply(ab[0])
        .add(ab[1])
        .setScale(SCALE, RoundingMode.HALF_UP)
        .stripTrailingZeros();
  }
}
