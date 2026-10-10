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
 * 盈透日线与富途日 K 的接缝核对（量化选股，ARCHITECTURE §25.6）。
 *
 * <p>两边口径不同：盈透已按拆股调整、富途不复权，价格水平差一个常数倍，所以<b>比日收益而不比价格</b>。
 * 只在两边都有收盘的相邻日上比（两边各自缺的日子不会让区间错位）；区间内有股数变动事件（拆股、合股、送转、分拆）
 * 的那一对跳过——除权当天两边一个调整一个不调整，收益本来就对不上。分红两边都不调整，不用跳过。
 *
 * <p>本类是量化选股的独立实现，与入场信号的结构口径（{@code domain.signal}）互不引用。
 */
public final class PriceSeam {

    /**
     * @param minCompared 至少比较多少对才下结论
     * @param tolerance   单日收益差的容忍度（0.001 = 0.1%）
     * @param minShare    达标对数占比下限
     */
    public record Thresholds(int minCompared, double tolerance, double minShare) {
        public static final Thresholds DEFAULT = new Thresholds(60, 0.001, 0.95);
    }

    /**
     * @param overlapDays 两边都有收盘的天数
     * @param comparedDays 实际比较的相邻日对数（跳过股数变动后）
     * @param withinDays  收益差在容忍度内的对数
     * @param maxAbsDiff  最大单日收益差（比例），没有可比较的对时为 null
     */
    public record Result(int overlapDays, int comparedDays, int withinDays, BigDecimal maxAbsDiff, boolean ok, String detail) {
    }

    private PriceSeam() {
    }

    /**
     * @param ibkr             盈透拆股调整日线
     * @param futuCloses       富途不复权收盘（只放有成交的 K 线）
     * @param shareChangeDates 股数变动事件的除权日
     */
    public static Result check(List<SplitAdjustedBar> ibkr, Map<LocalDate, BigDecimal> futuCloses,
                               Set<LocalDate> shareChangeDates, Thresholds t) {
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
            detail = "达标 " + within + "/" + compared + " 对（容忍 " + t.tolerance() * 100 + "%），最大收益差 "
                    + maxDiff.movePointRight(2).stripTrailingZeros().toPlainString() + "%";
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
