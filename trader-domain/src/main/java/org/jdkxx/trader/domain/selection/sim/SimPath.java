package org.jdkxx.trader.domain.selection.sim;

import org.jdkxx.trader.domain.signal.Indicators;
import org.jdkxx.trader.domain.signal.SignalBar;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * 一笔模拟交易从评分日走到截止日（纯函数）。每次都从加入时定下的价位<b>整段重算</b>，不存增量状态——
 * 持有期间的拆股（调用方按截止日做结构换算再折回评分日口径）、K 线订正都自然体现在重算里。
 *
 * <p>同一根 K 线内的先后：<b>开盘</b>（入场、手动平仓）→ 盘中（+1R、止盈目标是否触及，看最高价）→ <b>收盘</b>（止损判定）。
 * 手动平仓只在入场那根之后生效；止损按收盘判定、按收盘成交，跳空跌穿时照实记更大的亏损，不假装在止损价成交。
 * 止盈只记「哪天触及」，不自动平仓。
 */
public final class SimPath {

    private SimPath() {
    }

    public enum State {
        /** 入场日的 K 线还没有 */
        PENDING,
        OPEN,
        CLOSED,
        /** 入场开盘已在止损之下 */
        CANCELLED
    }

    public enum ExitReason {
        /** 收盘 ≤ 初始止损 */
        STOP,
        /** 收盘 ≤ 吊灯止损（已上移过初始止损） */
        CHANDELIER,
        /** 按请求在开盘平仓（调用方再分成止盈 / 手动） */
        REQUESTED
    }

    public enum TargetSource {
        RESISTANCE, FALLBACK_2R
    }

    /**
     * 价格都是评分日口径。
     *
     * @param highest  入场以来的最高价（盘中），算最大浮盈用
     * @param lowest   入场以来的最低价（盘中），算最大浮亏用
     * @param closes   持有期每个交易日的收盘（入场日起；开盘平仓的那天不含）：权益曲线用
     */
    public record Path(State state, LocalDate entryDate, Double entry, Double plusOneR, Double target, TargetSource targetSource,
                       boolean touchedPlusOneR, LocalDate targetHitDate, Double activeStop, boolean chandelierActive,
                       LocalDate exitDate, Double exit, ExitReason reason, Double highest, Double lowest, int barsHeld,
                       Double lastClose, LocalDate lastCloseDate, NavigableMap<LocalDate, Double> closes, String cancelReason) {

        /** 每股风险 R = 入场 − 止损。 */
        public Double risk(double stop) {
            return entry == null ? null : entry - stop;
        }
    }

    /**
     * @param bars         评分日口径的日 K，升序，必须含评分日；从评分日往前留够算 ATR 的长度
     * @param plannedEntry 入场日下限：评分日之后、日期 ≥ 它的第一根 K 线开盘入场
     * @param stop         初始止损
     * @param resistance   上方最近压力区的区底，没有为 null
     * @param plannedExit  手动平仓日下限，没有请求为 null：入场之后、日期 ≥ 它的第一根 K 线开盘平仓
     */
    public static Path walk(List<SignalBar> bars, LocalDate setupDate, LocalDate plannedEntry, double stop, Double resistance,
                            LocalDate plannedExit, SimRules rules) {
        int n = bars.size();
        int setup = -1;
        for (int i = 0; i < n; i++) {
            if (i > 0 && !bars.get(i).date().isAfter(bars.get(i - 1).date())) {
                throw new IllegalArgumentException("K 线必须按日期严格升序：" + bars.get(i).date());
            }
            if (bars.get(i).date().equals(setupDate)) {
                setup = i;
            }
        }
        if (setup < 0) {
            throw new IllegalArgumentException(setupDate + " 没有 K 线");
        }
        int entryIdx = -1;
        for (int i = setup + 1; i < n; i++) {
            if (!bars.get(i).date().isBefore(plannedEntry)) {
                entryIdx = i;
                break;
            }
        }
        if (entryIdx < 0) {
            return new Path(State.PENDING, null, null, null, null, null, false, null, null, false, null, null, null,
                    null, null, 0, null, null, Collections.emptyNavigableMap(), null);
        }
        SignalBar first = bars.get(entryIdx);
        double entry = first.open();
        if (entry <= stop) {
            return new Path(State.CANCELLED, first.date(), entry, null, null, null, false, null, null, false, null, null, null,
                    null, null, 0, null, null, Collections.emptyNavigableMap(),
                    "开盘 " + fmt(entry) + " 已不高于止损 " + fmt(stop) + "，不入场");
        }
        double risk = entry - stop;
        double plusOneR = entry + risk;
        boolean useResistance = resistance != null && resistance > entry;
        double target = useResistance ? resistance : entry + rules.targetFallbackR() * risk;
        TargetSource source = useResistance ? TargetSource.RESISTANCE : TargetSource.FALLBACK_2R;

        double[] high = bars.stream().mapToDouble(SignalBar::high).toArray();
        double[] low = bars.stream().mapToDouble(SignalBar::low).toArray();
        double[] close = bars.stream().mapToDouble(SignalBar::close).toArray();
        double[] atr = Indicators.wilderAtr(high, low, close, rules.sentinel().atrPeriod());

        NavigableMap<LocalDate, Double> closes = new TreeMap<>();
        boolean touched = false;
        LocalDate targetHit = null;
        double highest = Double.NEGATIVE_INFINITY;
        double lowest = Double.POSITIVE_INFINITY;
        double activeStop = stop;
        boolean chandelierActive = false;
        for (int i = entryIdx; i < n; i++) {
            SignalBar b = bars.get(i);
            if (i > entryIdx && plannedExit != null && !b.date().isBefore(plannedExit)) {
                return closed(entry, plusOneR, target, source, touched, targetHit, activeStop, chandelierActive, bars.get(entryIdx).date(),
                        b.date(), b.open(), ExitReason.REQUESTED, highest, lowest, i - entryIdx, closes);
            }
            highest = Math.max(highest, b.high());
            lowest = Math.min(lowest, b.low());
            if (!touched && b.high() >= plusOneR) {
                touched = true;
            }
            if (targetHit == null && b.high() >= target) {
                targetHit = b.date();
            }
            if (touched && !Double.isNaN(atr[i])) {
                double hh = Double.NEGATIVE_INFINITY;
                for (int k = Math.max(0, i - rules.sentinel().chandelierPeriod() + 1); k <= i; k++) {
                    hh = Math.max(hh, high[k]);
                }
                double chandelier = hh - rules.sentinel().chandelierAtrMultiple() * atr[i];
                if (chandelier > activeStop) {
                    activeStop = chandelier;      // 只上移
                    chandelierActive = true;
                }
            }
            if (b.close() <= activeStop) {
                closes.put(b.date(), b.close());
                return closed(entry, plusOneR, target, source, touched, targetHit, activeStop, chandelierActive, bars.get(entryIdx).date(),
                        b.date(), b.close(), chandelierActive ? ExitReason.CHANDELIER : ExitReason.STOP, highest, lowest,
                        i - entryIdx + 1, closes);
            }
            closes.put(b.date(), b.close());
        }
        SignalBar last = bars.get(n - 1);
        return new Path(State.OPEN, bars.get(entryIdx).date(), entry, plusOneR, target, source, touched, targetHit, activeStop,
                chandelierActive, null, null, null, highest, lowest, n - entryIdx, last.close(), last.date(),
                Collections.unmodifiableNavigableMap(closes), null);
    }

    private static Path closed(double entry, double plusOneR, double target, TargetSource source, boolean touched, LocalDate targetHit,
                               double activeStop, boolean chandelierActive, LocalDate entryDate, LocalDate exitDate, double exit,
                               ExitReason reason, double highest, double lowest, int barsHeld, NavigableMap<LocalDate, Double> closes) {
        // 开盘平仓时，平仓价也是持有期里真实出现过的价格，计入最高 / 最低（入场后第二天开盘就平的，只有入场价和它）
        double hi = Math.max(Double.isInfinite(highest) ? entry : highest, exit);
        double lo = Math.min(Double.isInfinite(lowest) ? entry : lowest, exit);
        return new Path(State.CLOSED, entryDate, entry, plusOneR, target, source, touched, targetHit, activeStop, chandelierActive,
                exitDate, exit, reason, hi, lo, barsHeld, exit, exitDate, Collections.unmodifiableNavigableMap(closes), null);
    }

    private static String fmt(double x) {
        return java.math.BigDecimal.valueOf(x).setScale(4, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
