package org.jdkxx.trader.core.marketdata.bars;

import org.jdkxx.trader.domain.Adjustment;
import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.RehabFactor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 读取层复权（纯函数）：价 = 不复权价 × A + B；成交量不变。
 * 前复权对 exDate &gt; 交易日 的事件生效；后复权对 exDate ≤ 交易日 的事件生效。
 */
public final class BarAdjuster {

    private static final int SCALE = 6;
    private static final BigDecimal[] IDENTITY = {BigDecimal.ONE, BigDecimal.ZERO};

    private BarAdjuster() {
    }

    public static List<DailyBar> adjust(List<DailyBar> bars, List<RehabFactor> factors, Adjustment adjustment, FactorMode mode) {
        if (adjustment == Adjustment.NONE || factors.isEmpty() || bars.isEmpty()) {
            return bars;
        }
        List<RehabFactor> sorted = new ArrayList<>(factors);
        sorted.sort(Comparator.comparing(RehabFactor::exDate));
        List<DailyBar> out = new ArrayList<>(bars.size());
        for (DailyBar b : bars) {
            BigDecimal[] ab = adjustment == Adjustment.FORWARD ? forward(sorted, b, mode) : backward(sorted, b, mode);
            // 前收属于前一交易日，按前一日的事件集合折算（除权日当天那根的前收也要被当天事件调整）
            DailyBar prev = b.withPrices(b.open(), b.high(), b.low(), b.close(), b.lastClose());
            DailyBar prevDay = new DailyBar(b.instrument(), b.tradeDate().minusDays(1), b.open(), b.high(), b.low(), b.close(),
                    b.lastClose(), b.volume(), b.turnover(), b.turnoverRate(), b.changeRate(), b.pe(), b.blank());
            BigDecimal[] abPrev = adjustment == Adjustment.FORWARD ? forward(sorted, prevDay, mode) : backward(sorted, prevDay, mode);
            if (ab == null && abPrev == null) {
                out.add(b);
                continue;
            }
            BigDecimal[] a1 = ab == null ? IDENTITY : ab;
            BigDecimal[] a0 = abPrev == null ? IDENTITY : abPrev;
            out.add(prev.withPrices(apply(b.open(), a1), apply(b.high(), a1), apply(b.low(), a1), apply(b.close(), a1),
                    b.lastClose() == null ? null : apply(b.lastClose(), a0)));
        }
        return out;
    }

    /** 返回 {A, B}；没有适用事件返回 null。 */
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
                // 先应用较早的事件，再应用较晚的：p' = A2 (A1 p + B1) + B2
                bb = f.fwdA().multiply(bb).add(f.fwdB());
                a = f.fwdA().multiply(a);
            }
        }
        return a == null ? null : new BigDecimal[] {a, bb};
    }

    /**
     * 后复权：对 exDate ≤ 交易日 的事件生效。逐事件模式按<b>从最近到最早</b>复合：
     * p' = A_earliest(… (A_latest p + B_latest) …) + B_earliest —— 后来的分红要按更早的拆股比例放大
     * （实测：升序复合与富途后复权序列差 2.5%，降序才一致）。
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
        return price.multiply(ab[0]).add(ab[1]).setScale(SCALE, RoundingMode.HALF_UP).stripTrailingZeros();
    }
}
