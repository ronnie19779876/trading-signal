package org.jdkxx.trader.domain.selection.backtest;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.ToDoubleFunction;
import org.jdkxx.trader.domain.selection.Factor;
import org.jdkxx.trader.domain.selection.Metric;
import org.jdkxx.trader.domain.selection.Rating;

/**
 * Statistics of the historical test (ARCHITECTURE §30.4). Input is one list of observations per
 * sample date; dates must be non-overlapping (step ≥ h) for the t values to mean anything, which
 * the caller guarantees. Every per-date statistic is computed first and then summarised across
 * dates, so each date weighs the same however many names it has.
 */
public final class BacktestStats {

  /** Fewest observations on a date for a rank correlation to be computed */
  static final int MIN_IC_N = 10;

  private BacktestStats() {}

  /**
   * One rated instrument on one sample date with a finite forward return.
   *
   * @param nameYearBase same-name same-year baseline (NaN when there is none)
   */
  public record Obs(
      long instrumentId,
      String sector,
      Rating rating,
      double composite,
      Map<Factor, Double> factors,
      Map<Metric, Double> metricPct,
      double ret,
      double nameYearBase) {}

  /** Observations of one sample date; {@code attrition} counts rated names without a return. */
  public record DateSample(LocalDate date, List<Obs> obs, Map<Rating, Integer> attrition) {}

  /** Benchmarks for excess returns (§30.3). */
  public enum Excess {
    /** Equal weight of the same sector on the same date (primary) */
    SECTOR,
    /** Equal weight of all rated names on the same date */
    UNIVERSE,
    /** The same name entered on any day of the same year */
    NAME_YEAR
  }

  /**
   * Summary of a per-date series.
   *
   * @param hit share of dates with a value above zero
   */
  public record Summary(int n, double mean, double sd, double t, double hit) {
    static final Summary EMPTY = new Summary(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
  }

  /** Per-year breakdown: SB sector excess and composite IC, summarised over that year's dates. */
  public record YearRow(Summary sbSectorExcess, Summary compositeIc) {}

  /** Pooled SB sector excess within one sector (observations, not dates). */
  public record SectorRow(int n, double meanSbSectorExcess) {}

  public record Report(
      int dates,
      int observations,
      Map<Rating, Integer> attrition,
      Summary compositeIc,
      Map<Factor, Summary> factorIc,
      Map<Metric, Summary> metricIc,
      Map<Excess, Map<Rating, Summary>> ratingExcess,
      Map<Excess, Summary> sbMinusSs,
      Map<Excess, Double> monotonicity,
      Map<Integer, YearRow> byYear,
      Map<String, SectorRow> bySector,
      Summary sbTurnover,
      Map<String, Double> factorCorrelation) {}

  /** Headline numbers only (used for the start-offset robustness rows). */
  public record Headline(int dates, Summary compositeIc, Summary sbSectorExcess) {}

  public static Report report(List<DateSample> samples) {
    Map<Rating, Integer> attrition = new EnumMap<>(Rating.class);
    List<Double> compIc = new ArrayList<>();
    Map<Factor, List<Double>> factorIc = new EnumMap<>(Factor.class);
    Map<Metric, List<Double>> metricIc = new EnumMap<>(Metric.class);
    Map<Excess, Map<Rating, List<Double>>> bucket = new EnumMap<>(Excess.class);
    Map<Excess, List<Double>> spread = new EnumMap<>(Excess.class);
    Map<Integer, List<Double>> yearSb = new TreeMap<>();
    Map<Integer, List<Double>> yearIc = new TreeMap<>();
    Map<String, double[]> sector = new TreeMap<>(); // sector -> [sum, n]
    List<Double> turnover = new ArrayList<>();
    Set<Factor> seenFactors = new HashSet<>();
    List<Map<Factor, Double>> pooledFactors = new ArrayList<>();
    Set<Long> prevSb = null;
    int observations = 0;

    for (DateSample d : samples) {
      d.attrition().forEach((r, n) -> attrition.merge(r, n, Integer::sum));
      List<Obs> obs = d.obs();
      observations += obs.size();
      double ic = spearman(obs, Obs::composite);
      add(compIc, ic);
      for (Factor f : Factor.values()) {
        List<Obs> has = obs.stream().filter(o -> o.factors().get(f) != null).toList();
        if (!has.isEmpty()) {
          seenFactors.add(f);
          add(
              factorIc.computeIfAbsent(f, k -> new ArrayList<>()),
              spearman(has, o -> o.factors().get(f)));
        }
      }
      for (Metric m : Metric.values()) {
        List<Obs> has = obs.stream().filter(o -> o.metricPct().get(m) != null).toList();
        if (!has.isEmpty()) {
          add(
              metricIc.computeIfAbsent(m, k -> new ArrayList<>()),
              spearman(has, o -> o.metricPct().get(m)));
        }
      }
      Map<Excess, double[]> excess = excess(obs);
      for (Excess e : Excess.values()) {
        double[] ex = excess.get(e);
        Map<Rating, Double> means = bucketMeans(obs, ex);
        Map<Rating, List<Double>> b = bucket.computeIfAbsent(e, k -> new EnumMap<>(Rating.class));
        means.forEach((r, v) -> b.computeIfAbsent(r, k -> new ArrayList<>()).add(v));
        Double sb = means.get(Rating.STRONG_BUY);
        Double ss = means.get(Rating.STRONG_SELL);
        if (sb != null && ss != null) {
          spread.computeIfAbsent(e, k -> new ArrayList<>()).add(sb - ss);
        }
        if (e == Excess.SECTOR && sb != null) {
          yearSb.computeIfAbsent(d.date().getYear(), k -> new ArrayList<>()).add(sb);
        }
      }
      add(yearIc.computeIfAbsent(d.date().getYear(), k -> new ArrayList<>()), ic);
      double[] sectorEx = excess.get(Excess.SECTOR);
      Set<Long> sb = new HashSet<>();
      for (int i = 0; i < obs.size(); i++) {
        Obs o = obs.get(i);
        pooledFactors.add(o.factors());
        if (o.rating() == Rating.STRONG_BUY) {
          sb.add(o.instrumentId());
          if (Double.isFinite(sectorEx[i])) {
            double[] acc = sector.computeIfAbsent(o.sector(), k -> new double[2]);
            acc[0] += sectorEx[i];
            acc[1]++;
          }
        }
      }
      if (prevSb != null && !prevSb.isEmpty()) {
        Set<Long> kept = new HashSet<>(prevSb);
        kept.retainAll(sb);
        turnover.add(1 - (double) kept.size() / prevSb.size());
      }
      prevSb = sb;
    }

    Map<Factor, Summary> fIc = new EnumMap<>(Factor.class);
    factorIc.forEach((f, v) -> fIc.put(f, summarize(v)));
    Map<Metric, Summary> mIc = new EnumMap<>(Metric.class);
    metricIc.forEach((m, v) -> mIc.put(m, summarize(v)));
    Map<Excess, Map<Rating, Summary>> rEx = new EnumMap<>(Excess.class);
    Map<Excess, Double> mono = new EnumMap<>(Excess.class);
    bucket.forEach(
        (e, byRating) -> {
          Map<Rating, Summary> s = new EnumMap<>(Rating.class);
          byRating.forEach((r, v) -> s.put(r, summarize(v)));
          rEx.put(e, s);
          mono.put(e, monotonicity(s));
        });
    Map<Excess, Summary> spr = new EnumMap<>(Excess.class);
    spread.forEach((e, v) -> spr.put(e, summarize(v)));
    Map<Integer, YearRow> years = new TreeMap<>();
    for (Integer y : yearIc.keySet()) {
      years.put(
          y, new YearRow(summarize(yearSb.getOrDefault(y, List.of())), summarize(yearIc.get(y))));
    }
    Map<String, SectorRow> sectors = new LinkedHashMap<>();
    sector.forEach((k, v) -> sectors.put(k, new SectorRow((int) v[1], v[0] / v[1])));
    return new Report(
        samples.size(),
        observations,
        attrition,
        summarize(compIc),
        fIc,
        mIc,
        rEx,
        spr,
        mono,
        years,
        sectors,
        summarize(turnover),
        factorCorrelation(pooledFactors, seenFactors));
  }

  public static Headline headline(List<DateSample> samples) {
    List<Double> ic = new ArrayList<>();
    List<Double> sb = new ArrayList<>();
    for (DateSample d : samples) {
      add(ic, spearman(d.obs(), Obs::composite));
      Double m = bucketMeans(d.obs(), excess(d.obs()).get(Excess.SECTOR)).get(Rating.STRONG_BUY);
      if (m != null) {
        sb.add(m);
      }
    }
    return new Headline(samples.size(), summarize(ic), summarize(sb));
  }

  /** Excess return of each observation (same order) against each benchmark; NaN when undefined. */
  static Map<Excess, double[]> excess(List<Obs> obs) {
    int n = obs.size();
    double all = 0;
    Map<String, double[]> bySector = new HashMap<>();
    for (Obs o : obs) {
      all += o.ret();
      double[] acc = bySector.computeIfAbsent(o.sector(), k -> new double[2]);
      acc[0] += o.ret();
      acc[1]++;
    }
    double universe = n == 0 ? Double.NaN : all / n;
    double[] sec = new double[n];
    double[] uni = new double[n];
    double[] name = new double[n];
    for (int i = 0; i < n; i++) {
      Obs o = obs.get(i);
      double[] acc = bySector.get(o.sector());
      sec[i] = o.ret() - acc[0] / acc[1];
      uni[i] = o.ret() - universe;
      name[i] = Double.isFinite(o.nameYearBase()) ? o.ret() - o.nameYearBase() : Double.NaN;
    }
    Map<Excess, double[]> out = new EnumMap<>(Excess.class);
    out.put(Excess.SECTOR, sec);
    out.put(Excess.UNIVERSE, uni);
    out.put(Excess.NAME_YEAR, name);
    return out;
  }

  /** Mean excess per rating on one date (ratings with no finite value are absent). */
  static Map<Rating, Double> bucketMeans(List<Obs> obs, double[] excess) {
    Map<Rating, double[]> acc = new EnumMap<>(Rating.class);
    for (int i = 0; i < obs.size(); i++) {
      if (Double.isFinite(excess[i])) {
        double[] a = acc.computeIfAbsent(obs.get(i).rating(), k -> new double[2]);
        a[0] += excess[i];
        a[1]++;
      }
    }
    Map<Rating, Double> out = new EnumMap<>(Rating.class);
    acc.forEach((r, a) -> out.put(r, a[0] / a[1]));
    return out;
  }

  /**
   * Spearman correlation of a score with the forward return over one date's observations (ties get
   * average ranks); NaN with fewer than {@value #MIN_IC_N} observations or a constant side.
   */
  static double spearman(List<Obs> obs, ToDoubleFunction<Obs> score) {
    int n = obs.size();
    if (n < MIN_IC_N) {
      return Double.NaN;
    }
    double[] x = new double[n];
    double[] y = new double[n];
    for (int i = 0; i < n; i++) {
      x[i] = score.applyAsDouble(obs.get(i));
      y[i] = obs.get(i).ret();
    }
    return pearson(ranks(x), ranks(y));
  }

  /** Average ranks (0-based), ties sharing the mean of their positions. */
  static double[] ranks(double[] v) {
    int n = v.length;
    Integer[] idx = new Integer[n];
    for (int i = 0; i < n; i++) {
      idx[i] = i;
    }
    Arrays.sort(idx, Comparator.comparingDouble(i -> v[i]));
    double[] r = new double[n];
    int i = 0;
    while (i < n) {
      int j = i;
      while (j + 1 < n && v[idx[j + 1]] == v[idx[i]]) {
        j++;
      }
      double avg = (i + j) / 2.0;
      for (int k = i; k <= j; k++) {
        r[idx[k]] = avg;
      }
      i = j + 1;
    }
    return r;
  }

  static double pearson(double[] x, double[] y) {
    int n = x.length;
    double mx = 0;
    double my = 0;
    for (int i = 0; i < n; i++) {
      mx += x[i];
      my += y[i];
    }
    mx /= n;
    my /= n;
    double sxy = 0;
    double sxx = 0;
    double syy = 0;
    for (int i = 0; i < n; i++) {
      sxy += (x[i] - mx) * (y[i] - my);
      sxx += (x[i] - mx) * (x[i] - mx);
      syy += (y[i] - my) * (y[i] - my);
    }
    return sxx == 0 || syy == 0 ? Double.NaN : sxy / Math.sqrt(sxx * syy);
  }

  /**
   * Mean, sample standard deviation, t = mean ÷ (sd ÷ √n) and hit rate; NaN entries are skipped.
   */
  static Summary summarize(List<Double> values) {
    double[] v =
        values.stream().filter(Double::isFinite).mapToDouble(Double::doubleValue).toArray();
    int n = v.length;
    if (n == 0) {
      return Summary.EMPTY;
    }
    double mean = Arrays.stream(v).average().orElseThrow();
    double hit = Arrays.stream(v).filter(x -> x > 0).count() / (double) n;
    if (n < 2) {
      return new Summary(n, mean, Double.NaN, Double.NaN, hit);
    }
    double ss = 0;
    for (double x : v) {
      ss += (x - mean) * (x - mean);
    }
    double sd = Math.sqrt(ss / (n - 1));
    return new Summary(n, mean, sd, sd == 0 ? Double.NaN : mean / (sd / Math.sqrt(n)), hit);
  }

  /**
   * Spearman of rating order (STRONG_BUY best) with the mean excess per rating: +1 means the mean
   * excess falls strictly from STRONG_BUY to STRONG_SELL. NaN with fewer than 3 ratings.
   */
  static double monotonicity(Map<Rating, Summary> byRating) {
    List<double[]> pts = new ArrayList<>();
    for (Rating r : Rating.values()) {
      Summary s = byRating.get(r);
      if (s != null && Double.isFinite(s.mean())) {
        pts.add(new double[] {Rating.values().length - 1 - r.ordinal(), s.mean()});
      }
    }
    if (pts.size() < 3) {
      return Double.NaN;
    }
    double[] x = pts.stream().mapToDouble(p -> p[0]).toArray();
    double[] y = pts.stream().mapToDouble(p -> p[1]).toArray();
    return pearson(ranks(x), ranks(y));
  }

  /** Pooled Pearson correlation of factor scores, for every pair of factors that occurs. */
  static Map<String, Double> factorCorrelation(List<Map<Factor, Double>> pooled, Set<Factor> seen) {
    Map<String, Double> out = new LinkedHashMap<>();
    Factor[] fs = Factor.values();
    for (int a = 0; a < fs.length; a++) {
      for (int b = a + 1; b < fs.length; b++) {
        if (!seen.contains(fs[a]) || !seen.contains(fs[b])) {
          continue;
        }
        Factor fa = fs[a];
        Factor fb = fs[b];
        List<Map<Factor, Double>> both =
            pooled.stream().filter(m -> m.get(fa) != null && m.get(fb) != null).toList();
        double[] x = both.stream().mapToDouble(m -> m.get(fa)).toArray();
        double[] y = both.stream().mapToDouble(m -> m.get(fb)).toArray();
        out.put(fa + "~" + fb, x.length < 3 ? Double.NaN : pearson(x, y));
      }
    }
    return out;
  }

  private static void add(List<Double> list, double v) {
    if (Double.isFinite(v)) {
      list.add(v);
    }
  }
}
