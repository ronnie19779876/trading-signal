package org.jdkxx.trader.domain.selection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 量化选股评分模型 F1（ARCHITECTURE §25.4）。纯函数：同一份输入永远给同一份结果。
 * <ol>
 *   <li>板块组内合格标的不足 {@value #MIN_SECTOR_SIZE} 只，整组记 {@link Exclusion#SECTOR_TOO_SMALL}；</li>
 *   <li>指标百分位：同板块、该指标有值的合格标的内排名，(名次 − 1) ÷ (n − 1) × 100，并列取平均名次；n &lt; {@value #MIN_METRIC_N} 该板块该指标作废；</li>
 *   <li>因子分：有值指标百分位的平均，至少一半适用指标有值；</li>
 *   <li>合成分：四个因子等权平均，四个都有才评级；</li>
 *   <li>评级：按合成分强制分布 前 10% / 10~30% / 30~70% / 70~90% / 后 10%（用户 2026-10-10 定）。</li>
 * </ol>
 * 参数随模型版本固定在代码里，改参数就是换模型版本。
 */
public final class SelectionModel {

    public static final String VERSION = "F1";
    public static final int MIN_SECTOR_SIZE = 8;
    public static final int MIN_METRIC_N = 5;
    /** 评级切点：位置（0 = 最好）占比小于这些值依次是 STRONG_BUY、BUY、HOLD、SELL，其余 STRONG_SELL */
    static final double[] CUTS = {0.10, 0.30, 0.70, 0.90};

    private SelectionModel() {
    }

    public static List<StockScore> score(List<StockInputs> inputs) {
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
            } else if (sectorSize.get(s.sector()) < MIN_SECTOR_SIZE) {
                exclusion.put(s.instrumentId(), Exclusion.SECTOR_TOO_SMALL);
                detail.put(s.instrumentId(), s.sector() + " 合格 " + sectorSize.get(s.sector()) + " 只，不足 " + MIN_SECTOR_SIZE);
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
                List<StockInputs> has = new ArrayList<>();
                for (StockInputs s : group) {
                    Double v = s.metrics().get(m);
                    if (v != null && Double.isFinite(v)) {
                        has.add(s);
                    }
                }
                int n = has.size();
                if (n < MIN_METRIC_N) {
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
                    double avgRank = (i + j) / 2.0;   // 0 起的平均名次
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
                int applicable = 0;
                double sum = 0;
                int present = 0;
                for (Metric m : Metric.values()) {
                    if (m.factor() != f || !s.applicable().contains(m)) {
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
            if (fs.size() == Factor.values().length) {
                composite.put(s.instrumentId(), fs.values().stream().mapToDouble(Double::doubleValue).average().orElseThrow());
            }
        }

        List<Long> ranked = new ArrayList<>(composite.keySet());
        ranked.sort(Comparator.<Long>comparingDouble(id -> -composite.get(id)).thenComparing(id -> id));
        Map<Long, Rating> rating = new HashMap<>();
        Map<Long, Double> compositePct = new HashMap<>();
        int total = ranked.size();
        for (int i = 0; i < total; i++) {
            long id = ranked.get(i);
            double position = (double) i / total;
            rating.put(id, position < CUTS[0] ? Rating.STRONG_BUY : position < CUTS[1] ? Rating.BUY
                    : position < CUTS[2] ? Rating.HOLD : position < CUTS[3] ? Rating.SELL : Rating.STRONG_SELL);
            compositePct.put(id, total == 1 ? 100.0 : (double) (total - 1 - i) / (total - 1) * 100);
        }

        List<StockScore> out = new ArrayList<>(inputs.size());
        for (StockInputs s : inputs) {
            long id = s.instrumentId();
            out.add(new StockScore(id, s.symbol(), s.sector(), exclusion.get(id), detail.get(id), s.metrics(),
                    pct.getOrDefault(id, Map.of()), factors.getOrDefault(id, Map.of()), composite.get(id),
                    compositePct.get(id), rating.get(id)));
        }
        return out;
    }
}
