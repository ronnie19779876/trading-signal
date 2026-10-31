package org.jdkxx.trader.domain.selection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Quant selection scoring model (ARCHITECTURE §25.4); parameters come from a {@link ModelSpec}, and
 * production uses {@link ModelSpec#F1}. Pure function: the same input always yields the same
 * result.
 *
 * <ol>
 *   <li>a sector group with fewer than {@code minSectorSize} eligible instruments is marked {@link
 *       Exclusion#SECTOR_TOO_SMALL} as a whole;
 *   <li>metric percentile: rank among eligible instruments of the same sector that have a value for
 *       the metric, (rank − 1) ÷ (n − 1) × 100 with ties getting the average rank (ascending, so
 *       higher values get higher percentiles); with n &lt; {@code minMetricN} the metric is void
 *       for that sector;
 *   <li>factor score: average of the percentiles of the metrics that have values, provided at least
 *       half of the applicable metrics have one;
 *   <li>composite score: equal-weighted average of the spec's factors; rated only when all of them
 *       exist;
 *   <li>rating: forced distribution on the composite over the spec's rating scope (F1: all rated
 *       instruments, top 10% / 10~30% / 30~70% / 70~90% / bottom 10%, set by the user on
 *       2026-10-10). Equal composites are ordered by instrument id so the result is deterministic.
 * </ol>
 *
 * Changing a parameter of the production model means a new model version.
 */
public final class SelectionModel {

  public static final String VERSION = "F1";

  private SelectionModel() {}

  /** Scores with the production model F1. */
  public static List<StockScore> score(List<StockInputs> inputs) {
    return score(inputs, ModelSpec.F1);
  }

  public static List<StockScore> score(List<StockInputs> inputs, ModelSpec spec) {
    Map<String, Integer> sectorSize = new HashMap<>();
    for (StockInputs s : inputs) {
      if (s.exclusion() == null) {
        sectorSize.merge(s.sector(), 1, Integer::sum);
      }
    }
    List<StockInputs> eligible = new ArrayList<>();
    Map<Long, Exclusion> exclusion = new HashMap<>();
    Map<Long, String> detail = new HashMap<>();
    for (StockInputs s : inputs) {
      if (s.exclusion() != null) {
        exclusion.put(s.instrumentId(), s.exclusion());
        detail.put(s.instrumentId(), s.exclusionDetail());
      } else if (sectorSize.get(s.sector()) < spec.minSectorSize()) {
        exclusion.put(s.instrumentId(), Exclusion.SECTOR_TOO_SMALL);
        detail.put(
            s.instrumentId(),
            s.sector() + " 合格 " + sectorSize.get(s.sector()) + " 只，不足 " + spec.minSectorSize());
      } else {
        eligible.add(s);
      }
    }

    Map<Long, Map<Metric, Double>> pct = new HashMap<>();
    eligible.forEach(s -> pct.put(s.instrumentId(), new EnumMap<>(Metric.class)));
    Map<String, List<StockInputs>> bySector = new HashMap<>();
    eligible.forEach(s -> bySector.computeIfAbsent(s.sector(), k -> new ArrayList<>()).add(s));
    for (List<StockInputs> group : bySector.values()) {
      for (Metric m : Metric.values()) {
        if (!spec.uses(m)) {
          continue;
        }
        List<StockInputs> has = new ArrayList<>();
        for (StockInputs s : group) {
          Double v = s.metrics().get(m);
          if (v != null && Double.isFinite(v)) {
            has.add(s);
          }
        }
        int n = has.size();
        if (n < spec.minMetricN()) {
          continue;
        }
        has.sort(Comparator.comparingDouble(s -> s.metrics().get(m)));
        int i = 0;
        while (i < n) {
          int j = i;
          double v = has.get(i).metrics().get(m);
          while (j + 1 < n && has.get(j + 1).metrics().get(m) == v) {
            j++;
          }
          double avgRank = (i + j) / 2.0; // average 0-based rank
          double p = avgRank / (n - 1) * 100;
          for (int k = i; k <= j; k++) {
            pct.get(has.get(k).instrumentId()).put(m, p);
          }
          i = j + 1;
        }
      }
    }

    Map<Long, Map<Factor, Double>> factors = new HashMap<>();
    Map<Long, Double> composite = new HashMap<>();
    for (StockInputs s : eligible) {
      Map<Metric, Double> p = pct.get(s.instrumentId());
      Map<Factor, Double> fs = new EnumMap<>(Factor.class);
      for (Factor f : Factor.values()) {
        if (!spec.factors().contains(f)) {
          continue;
        }
        int applicable = 0;
        double sum = 0;
        int present = 0;
        for (Metric m : Metric.values()) {
          if (m.factor() != f || !spec.uses(m) || !s.applicable().contains(m)) {
            continue;
          }
          applicable++;
          Double v = p.get(m);
          if (v != null) {
            sum += v;
            present++;
          }
        }
        if (applicable > 0 && present * 2 >= applicable) {
          fs.put(f, sum / present);
        }
      }
      factors.put(s.instrumentId(), fs);
      if (fs.size() == spec.factors().size()) {
        composite.put(
            s.instrumentId(),
            fs.values().stream().mapToDouble(Double::doubleValue).average().orElseThrow());
      }
    }

    Map<Long, Rating> rating = new HashMap<>();
    Map<Long, Double> compositePct = new HashMap<>();
    Map<String, List<Long>> groups = new HashMap<>();
    Map<Long, String> sectorOf = new HashMap<>();
    eligible.forEach(s -> sectorOf.put(s.instrumentId(), s.sector()));
    for (Long id : composite.keySet()) {
      String key = spec.ratingScope() == ModelSpec.RatingScope.SECTOR ? sectorOf.get(id) : "";
      groups.computeIfAbsent(key, k -> new ArrayList<>()).add(id);
    }
    for (List<Long> ranked : groups.values()) {
      ranked.sort(
          Comparator.<Long>comparingDouble(id -> -composite.get(id)).thenComparing(id -> id));
      int total = ranked.size();
      for (int i = 0; i < total; i++) {
        long id = ranked.get(i);
        rating.put(id, spec.rating((double) i / total));
        compositePct.put(id, total == 1 ? 100.0 : (double) (total - 1 - i) / (total - 1) * 100);
      }
    }

    List<StockScore> out = new ArrayList<>(inputs.size());
    for (StockInputs s : inputs) {
      long id = s.instrumentId();
      out.add(
          new StockScore(
              id,
              s.symbol(),
              s.sector(),
              exclusion.get(id),
              detail.get(id),
              s.metrics(),
              pct.getOrDefault(id, Map.of()),
              factors.getOrDefault(id, Map.of()),
              composite.get(id),
              compositePct.get(id),
              rating.get(id)));
    }
    return out;
  }
}
