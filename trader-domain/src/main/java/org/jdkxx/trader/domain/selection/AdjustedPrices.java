package org.jdkxx.trader.domain.selection;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;
import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.RehabFactor;

/**
 * Price series for quant selection (§25.6): <b>split-adjusted prices</b>, adjusted only by the
 * exact share-count ratio (split, consolidation, bonus shares) and never for dividends or
 * spin-offs; this matches IBKR's TRADES basis, which is what lets the two segments be spliced. The
 * Futu segment is the unadjusted price × the share-count factor; the IBKR segment (used only when
 * the seam check passed) is scaled by the price ratio on the first common trading day and
 * prepended, so later splits never require re-fetching it. The series also carries the day's
 * <b>unadjusted price</b> (for the "price &gt;= 10 USD" rule): the original value for the Futu
 * segment, and the adjusted price divided by the share-count factor for the IBKR segment.
 *
 * <p>An independent implementation that does not reference the entry signal's structural basis. An
 * event with a share-count bit but no ratio, or a capitalization issue (never verified),
 * invalidates the whole series with a stated reason instead of guessing.
 */
public final class AdjustedPrices {

  private static final long SHARE_EVENTS =
      RehabFactor.ACT_SPLIT
          | RehabFactor.ACT_JOIN
          | RehabFactor.ACT_BONUS
          | RehabFactor.ACT_TRANSFER;

  private final LocalDate[] dates;
  private final double[] adjusted;
  private final double[] raw;
  private final String problem;

  private AdjustedPrices(LocalDate[] dates, double[] adjusted, double[] raw, String problem) {
    this.dates = dates;
    this.adjusted = adjusted;
    this.raw = raw;
    this.problem = problem;
  }

  public static AdjustedPrices invalid(String problem) {
    return new AdjustedPrices(new LocalDate[0], new double[0], new double[0], problem);
  }

  /**
   * @param futu Futu unadjusted daily bars (blank bars are dropped)
   * @param factors price-adjustment factors (only share-count events are used)
   * @param ibkr IBKR split-adjusted daily bars, ascending; pass an empty list when the seam check
   *     failed or there are none. If the first IBKR bar on or after the Futu start has no Futu bar
   *     on the same date, the IBKR segment is silently not used
   */
  public static AdjustedPrices build(
      List<DailyBar> futu, List<RehabFactor> factors, List<SplitAdjustedBar> ibkr) {
    List<double[]> events = new ArrayList<>(); // [exDate epochDay, priceFactor = 1 / share ratio]
    for (RehabFactor f : factors) {
      if ((f.companyActFlag() & SHARE_EVENTS) == 0) {
        continue;
      }
      if (f.has(RehabFactor.ACT_TRANSFER)) {
        return invalid(f.exDate() + " 转增事件口径未实测（flag=" + f.companyActFlag() + "）");
      }
      double shares = 1;
      if (f.has(RehabFactor.ACT_SPLIT)) {
        if (f.splitBase() <= 0 || f.splitErt() <= 0) {
          return invalid(f.exDate() + " 拆股缺比例");
        }
        shares *= (double) f.splitErt() / f.splitBase();
      }
      if (f.has(RehabFactor.ACT_JOIN)) {
        if (f.joinBase() <= 0 || f.joinErt() <= 0) {
          return invalid(f.exDate() + " 合股缺比例");
        }
        shares *= (double) f.joinErt() / f.joinBase();
      }
      if (f.has(RehabFactor.ACT_BONUS)) {
        if (f.bonusBase() <= 0 || f.bonusErt() <= 0) {
          return invalid(f.exDate() + " 送股缺比例");
        }
        shares *= (double) (f.bonusBase() + f.bonusErt()) / f.bonusBase();
      }
      events.add(new double[] {f.exDate().toEpochDay(), 1 / shares});
    }
    TreeMap<LocalDate, double[]> series = new TreeMap<>(); // date -> [adjusted, raw]
    for (DailyBar b : futu) {
      if (b.blank() || b.close() == null || b.close().signum() <= 0) {
        continue;
      }
      double r = b.close().doubleValue();
      series.put(b.tradeDate(), new double[] {r * factor(events, b.tradeDate()), r});
    }
    if (!ibkr.isEmpty() && !series.isEmpty()) {
      LocalDate first = series.firstKey();
      SplitAdjustedBar anchor = null;
      for (SplitAdjustedBar b : ibkr) {
        if (!b.tradeDate().isBefore(first)) {
          anchor = b;
          break;
        }
      }
      if (anchor != null && series.containsKey(anchor.tradeDate()) && anchor.close().signum() > 0) {
        double k = series.get(anchor.tradeDate())[0] / anchor.close().doubleValue();
        for (SplitAdjustedBar b : ibkr) {
          if (b.tradeDate().isBefore(first) && b.close().signum() > 0) {
            double adj = b.close().doubleValue() * k;
            series.put(b.tradeDate(), new double[] {adj, adj / factor(events, b.tradeDate())});
          }
        }
      }
    }
    LocalDate[] d = series.keySet().toArray(new LocalDate[0]);
    double[] a = new double[d.length];
    double[] r = new double[d.length];
    for (int i = 0; i < d.length; i++) {
      double[] v = series.get(d[i]);
      a[i] = v[0];
      r[i] = v[1];
    }
    return new AdjustedPrices(d, a, r, null);
  }

  /**
   * Price factor for date d: the product over share-count events whose ex-date is after d (forward
   * adjustment anchored on today).
   */
  private static double factor(List<double[]> events, LocalDate d) {
    double f = 1;
    long day = d.toEpochDay();
    for (double[] e : events) {
      if (e[0] > day) {
        f *= e[1];
      }
    }
    return f;
  }

  public String problem() {
    return problem;
  }

  public boolean valid() {
    return problem == null;
  }

  public int size() {
    return dates.length;
  }

  public LocalDate firstDate() {
    return dates.length == 0 ? null : dates[0];
  }

  /**
   * Index of the last bar on or before t; -1 if there is none or it is more than {@code
   * maxStaleDays} calendar days before t.
   */
  public int indexAt(LocalDate t, int maxStaleDays) {
    int i = Arrays.binarySearch(dates, t);
    if (i < 0) {
      i = -i - 2;
    }
    if (i < 0 || ChronoUnit.DAYS.between(dates[i], t) > maxStaleDays) {
      return -1;
    }
    return i;
  }

  public double rawClose(int i) {
    return raw[i];
  }

  /** Return over the k trading days (bars) ending at index i; NaN if the series is too short. */
  public double returnOver(int i, int k) {
    if (i - k < 0) {
      return Double.NaN;
    }
    return adjusted[i] / adjusted[i - k] - 1;
  }
}
