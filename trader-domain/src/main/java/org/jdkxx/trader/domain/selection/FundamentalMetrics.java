package org.jdkxx.trader.domain.selection;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 成长与盈利能力指标（§25.4），只用判定日已公告的季报。TTM = 最近 4 季之和，同比 = 与再往前 4 季比。
 * 要求最近 8 季期末连续（相邻 70~125 天），否则成长缺；盈利能力只要求最近 4 季连续（ROE 另要 4 季前的权益）。
 */
public final class FundamentalMetrics {

    /** 稀释 EPS 同比的分母下限：前 4 季 EPS 绝对值小于它时同比没有意义 */
    static final double EPS_BASE_MIN = 0.05;
    static final int MIN_GAP_DAYS = 70;
    static final int MAX_GAP_DAYS = 125;
    /** 期末相差不到这么多天的两季视为同一季（重复入库或改财年的过渡期），只留期末较晚的那条 */
    public static final int DUPLICATE_GAP_DAYS = 30;

    private FundamentalMetrics() {
    }

    /**
     * 去掉「同一季」的重复：期末相差不到 {@value #DUPLICATE_GAP_DAYS} 天的两条只留期末较晚的那条。
     * 两种来源（2026-10-12 全量实测 7 处）：富途不同时间抓取给的期末日期不同、同一期别存成两行（HD、NVDA、TDY，数值相同）；
     * 改财年的过渡期两条期别不同、数值不同（GEN、COHR），对照当年财报期末较晚的那条才对。quarters 按期末升序。
     */
    public static List<QuarterFacts> dedupe(List<QuarterFacts> quarters) {
        List<QuarterFacts> out = new ArrayList<>(quarters.size());
        for (QuarterFacts q : quarters) {
            if (!out.isEmpty() && ChronoUnit.DAYS.between(out.getLast().periodEnd(), q.periodEnd()) < DUPLICATE_GAP_DAYS) {
                out.removeLast();
            }
            out.add(q);
        }
        return out;
    }

    /** 截至 t 已公告的季报期数（§25.3 第 5 条用）。 */
    public static int availableCount(List<QuarterFacts> quarters, LocalDate t) {
        int n = 0;
        for (QuarterFacts q : quarters) {
            if (q.availableOn() != null && !q.availableOn().isAfter(t)) {
                n++;
            }
        }
        return n;
    }

    /** 截至 t 可算的指标；算不了的不放进结果。quarters 按期末从早到晚。 */
    public static Map<Metric, Double> compute(List<QuarterFacts> quarters, LocalDate t) {
        List<QuarterFacts> known = new ArrayList<>();
        for (QuarterFacts q : quarters) {
            if (q.availableOn() != null && !q.availableOn().isAfter(t)) {
                known.add(q);
            }
        }
        Map<Metric, Double> out = new EnumMap<>(Metric.class);
        int n = known.size();
        if (n < 4 || !consecutive(known, n - 4, n)) {
            return out;
        }
        List<QuarterFacts> cur = known.subList(n - 4, n);
        QuarterFacts.Template template = known.get(n - 1).template();
        Double rev = sum(cur, QuarterFacts::revenue);
        Double gross = sum(cur, QuarterFacts::grossProfit);
        Double ni = sum(cur, QuarterFacts::netIncome);
        Double fcf = sum(cur, QuarterFacts::freeCashFlow);
        if (rev != null && rev > 0) {
            if (template == QuarterFacts.Template.STANDARD && gross != null) {
                out.put(Metric.GROSS_MARGIN, gross / rev);
            }
            if (!template.financial() && fcf != null) {
                out.put(Metric.FCF_MARGIN, fcf / rev);
            }
        }
        if (n >= 5 && consecutive(known, n - 5, n)) {
            Double eqNow = known.get(n - 1).equity();
            Double eqThen = known.get(n - 5).equity();
            if (ni != null && eqNow != null && eqThen != null && eqNow > 0 && eqThen > 0) {
                out.put(Metric.ROE, ni / ((eqNow + eqThen) / 2));
            }
        }
        if (n >= 8 && consecutive(known, n - 8, n)) {
            List<QuarterFacts> prev = known.subList(n - 8, n - 4);
            Double revPrev = sum(prev, QuarterFacts::revenue);
            if (rev != null && revPrev != null && revPrev > 0) {
                out.put(Metric.REVENUE_GROWTH, rev / revPrev - 1);
            }
            Double eps = sum(cur, QuarterFacts::epsDiluted);
            Double epsPrev = sum(prev, QuarterFacts::epsDiluted);
            if (eps != null && epsPrev != null && Math.abs(epsPrev) >= EPS_BASE_MIN) {
                out.put(Metric.EPS_GROWTH, (eps - epsPrev) / Math.abs(epsPrev));
            }
        }
        return out;
    }

    /**
     * [from, to) 区间内相邻期末相隔 {@value #MIN_GAP_DAYS}~{@value #MAX_GAP_DAYS} 天。只用来识别缺季（缺一季约差 180 天）：
     * 12-12-12-16 周的财年（COST、KR、PEP、AZO、DPZ）季度长 84 天或 112~119 天，2026-10-12 全量实测分布 80~99 天 23193 个、
     * 110~119 天 62 个、180 天以上 13 个，原先的 80~100 天把 16 周那季误判成断档、成长整段算不出来。
     */
    static boolean consecutive(List<QuarterFacts> qs, int from, int to) {
        for (int i = from + 1; i < to; i++) {
            long d = ChronoUnit.DAYS.between(qs.get(i - 1).periodEnd(), qs.get(i).periodEnd());
            if (d < MIN_GAP_DAYS || d > MAX_GAP_DAYS) {
                return false;
            }
        }
        return true;
    }

    private static Double sum(List<QuarterFacts> qs, java.util.function.Function<QuarterFacts, Double> f) {
        double s = 0;
        for (QuarterFacts q : qs) {
            Double v = f.apply(q);
            if (v == null || !Double.isFinite(v)) {
                return null;
            }
            s += v;
        }
        return s;
    }
}
