package org.jdkxx.trader.domain.selection;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Scoring parameters of one quant selection model version (ARCHITECTURE §30.2). {@link #F1} is the
 * production model and equals the constants model F1 was built with; production scoring and replay
 * only ever use F1. Variants exist only for the historical test and are pre-registered in {@link
 * ModelSpecs}: the endpoint takes a spec name, never free parameters, so every variant ever tried
 * is a commit.
 *
 * <p>The data-hygiene rules of {@link FundamentalMetrics} and {@link QuarterAvailability} are not
 * part of the spec: the entry signal and the SOTP basis share them (§30.2).
 *
 * @param version spec name; for the production model it is the {@code model_version} column
 * @param metrics metrics the model computes and uses; a factor's "at least half of the metrics"
 *     rule counts only these
 * @param factors factors averaged into the composite; rated only when all of them exist
 * @param minSectorSize a sector group with fewer eligible instruments is excluded as a whole
 * @param minMetricN a metric with fewer values in a sector is void for that sector
 * @param cuts rating cut points on the position share (0 = best): STRONG_BUY, BUY, HOLD, SELL below
 *     these in turn, the rest STRONG_SELL
 * @param ratingScope over which group the forced distribution is applied
 * @param priceMin minimum unadjusted close (§25.3 rule 4)
 * @param minQuarters minimum number of announced quarterly reports (§25.3 rule 5)
 * @param staleDays calendar days a price or valuation may lag the scoring day
 * @param candidate false for diagnostic specs, which are not counted as tried variants
 */
public record ModelSpec(
    String version,
    String description,
    Set<Metric> metrics,
    Set<Factor> factors,
    int minSectorSize,
    int minMetricN,
    List<Double> cuts,
    RatingScope ratingScope,
    double priceMin,
    int minQuarters,
    int staleDays,
    boolean candidate) {

  /** Group a forced rating distribution is applied over. */
  public enum RatingScope {
    /** All rated instruments of the day ranked together (F1) */
    UNIVERSE,
    /** Each sector ranked on its own */
    SECTOR
  }

  /** The eleven metrics of F1 (every metric except the variant-only ones). */
  public static final Set<Metric> F1_METRICS =
      EnumSet.complementOf(EnumSet.of(Metric.MOMENTUM_12_1));

  public static final ModelSpec F1 =
      new ModelSpec(
          "F1",
          "生产模型：四因子等权，板块内百分位，全体强制分布 10/20/40/20/10",
          F1_METRICS,
          EnumSet.allOf(Factor.class),
          8,
          5,
          List.of(0.10, 0.30, 0.70, 0.90),
          RatingScope.UNIVERSE,
          10,
          8,
          7,
          true);

  public ModelSpec {
    Objects.requireNonNull(version, "version");
    // EnumSet copies keep enum order, so iteration (and the JSON of the spec) is deterministic
    metrics = Collections.unmodifiableSet(EnumSet.copyOf(metrics));
    factors = Collections.unmodifiableSet(EnumSet.copyOf(factors));
    cuts = List.copyOf(cuts);
    Objects.requireNonNull(ratingScope, "ratingScope");
    if (cuts.size() != Rating.values().length - 1) {
      throw new IllegalArgumentException("评级切点必须是 " + (Rating.values().length - 1) + " 个");
    }
    if (factors.isEmpty()) {
      throw new IllegalArgumentException("至少要有一个因子");
    }
    for (Factor f : factors) {
      if (metrics.stream().noneMatch(m -> m.factor() == f)) {
        throw new IllegalArgumentException(version + " 的因子 " + f + " 没有任何指标");
      }
    }
  }

  /** Whether the spec scores metric m: it is computed and its factor takes part. */
  public boolean uses(Metric m) {
    return metrics.contains(m) && factors.contains(m.factor());
  }

  /** Rating for a position share in [0, 1) of the ranked group. */
  Rating rating(double position) {
    for (int i = 0; i < cuts.size(); i++) {
      if (position < cuts.get(i)) {
        return Rating.values()[i];
      }
    }
    return Rating.STRONG_SELL;
  }

  ModelSpec with(String version, String description, Set<Metric> metrics, Set<Factor> factors) {
    return new ModelSpec(
        version,
        description,
        metrics,
        factors,
        minSectorSize,
        minMetricN,
        cuts,
        ratingScope,
        priceMin,
        minQuarters,
        staleDays,
        candidate);
  }

  ModelSpec withRatingScope(String version, String description, RatingScope scope) {
    return new ModelSpec(
        version,
        description,
        metrics,
        factors,
        minSectorSize,
        minMetricN,
        cuts,
        scope,
        priceMin,
        minQuarters,
        staleDays,
        candidate);
  }

  ModelSpec diagnostic() {
    return new ModelSpec(
        version,
        description,
        metrics,
        factors,
        minSectorSize,
        minMetricN,
        cuts,
        ratingScope,
        priceMin,
        minQuarters,
        staleDays,
        false);
  }
}
