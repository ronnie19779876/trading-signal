package org.jdkxx.trader.domain.signal;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Williams 分形与区间聚类（纯函数）。
 *
 * <p><b>无未来函数是结构性保证</b>：分形需要右侧 {@code side} 根 K 线确认，扫描上界是 {@code n−1−side}，
 * 数组最后 {@code side} 根在数学上不可能成为分形，判定日自身的高低点不会进入区间。回测与实盘因此同构。
 */
public final class Fractals {

    private Fractals() {
    }

    /** 分形低点下标：严格低于左右各 {@code side} 根。只返回下标 ≥ {@code from} 的。 */
    public static List<Integer> lows(double[] low, int side, int from) {
        return scan(low, side, from, true);
    }

    /** 分形高点下标：严格高于左右各 {@code side} 根。 */
    public static List<Integer> highs(double[] high, int side, int from) {
        return scan(high, side, from, false);
    }

    private static List<Integer> scan(double[] x, int side, int from, boolean lows) {
        List<Integer> out = new ArrayList<>();
        int last = x.length - 1 - side;
        for (int i = Math.max(side, from); i <= last; i++) {
            boolean fractal = true;
            for (int k = 1; k <= side && fractal; k++) {
                fractal = lows
                        ? x[i] < x[i - k] && x[i] < x[i + k]
                        : x[i] > x[i - k] && x[i] > x[i + k];
            }
            if (fractal) {
                out.add(i);
            }
        }
        return out;
    }

    /**
     * 按价格排序后单链接聚类：相邻价差 ≤ tolerance 归为同一簇，保留成员数 ≥ minTouches 的簇。
     * 返回的区间按区底升序。
     */
    public static List<PriceZone> cluster(List<Integer> indexes, double[] price, LocalDate[] dates,
                                          double tolerance, int minTouches) {
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

    private static void addIfQualified(List<PriceZone> zones, List<Integer> members, double[] price,
                                       LocalDate[] dates, int minTouches) {
        if (members.size() < minTouches) {
            return;
        }
        List<LocalDate> memberDates = members.stream().sorted().map(i -> dates[i]).toList();
        zones.add(new PriceZone(price[members.get(0)], price[members.get(members.size() - 1)], members.size(), memberDates));
    }
}
