package org.jdkxx.trader.domain.selection;

import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.RehabFactor;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;

/**
 * 量化选股的价格序列（§25.6）：<b>拆股调整价</b>，只按股数变动（拆股、合股、送股）的精确比例调整，不调分红与分拆——
 * 与盈透 TRADES 口径一致，两段才能拼接。富途段由不复权价 × 股数因子得到；盈透段（接缝通过才用）在第一个共同交易日按比值缩放拼上，
 * 之后再拆股也不必重抓。同时给出当天的<b>不复权价</b>（判定「股价 ≥ 10 美元」用）：富途段即原值，盈透段由调整价除以股数因子还原。
 *
 * <p>独立实现，不引用入场信号的结构口径。带股数变动标记却缺比例、或转增（口径未实测）的事件，整条序列作废并给出原因，不猜。
 */
public final class AdjustedPrices {

    private static final long SHARE_EVENTS = RehabFactor.ACT_SPLIT | RehabFactor.ACT_JOIN | RehabFactor.ACT_BONUS | RehabFactor.ACT_TRANSFER;

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
     * @param futu    富途不复权日 K（空 K 会被剔除）
     * @param factors 复权因子（只取股数变动事件）
     * @param ibkr    盈透拆股调整日线；接缝没通过或没有时传空表
     */
    public static AdjustedPrices build(List<DailyBar> futu, List<RehabFactor> factors, List<SplitAdjustedBar> ibkr) {
        List<double[]> events = new ArrayList<>();   // [exDate epochDay, priceFactor]
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
        TreeMap<LocalDate, double[]> series = new TreeMap<>();   // date → [adjusted, raw]
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

    /** 日期 d 的价格因子：除权日晚于 d 的股数事件之积（以今天为基准的前调整）。 */
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

    /** ≤ t 的最后一根的下标；没有或离 t 超过 maxStaleDays 天返回 -1。 */
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

    /** 从下标 i 往回 k 个交易日的涨幅；不够长返回 NaN。 */
    public double returnOver(int i, int k) {
        if (i - k < 0) {
            return Double.NaN;
        }
        return adjusted[i] / adjusted[i - k] - 1;
    }
}
