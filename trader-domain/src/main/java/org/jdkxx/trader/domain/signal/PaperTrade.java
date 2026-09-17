package org.jdkxx.trader.domain.signal;

import java.time.LocalDate;
import java.util.List;

/**
 * 纸面交易模拟（纯函数）：一条信号按出场预案逐日走到离场。纸面跟踪账本与回放统计共用。
 *
 * <p>建模假设（与 futu-trader 回测 A1~A8 对齐，差异单独标出）：
 * <ol>
 *   <li>入场：信号在判定日收盘后产生，按<b>次日开盘价</b>成交；</li>
 *   <li>R = 判定日收盘 − 止损，+1R = 判定日收盘 + R，都是绝对价位，不随入场价漂移；</li>
 *   <li>止损按<b>收盘价</b>判定（收盘 ≤ 止损）、当日收盘成交；</li>
 *   <li>吊灯止损逐日跟踪 = 近 22 日最高 − 3×ATR14(当日)，<b>盘中触及 +1R 之后</b>才生效，与初始止损取高者；</li>
 *   <li>时间止损：入场日算第 0 天，第 20 个交易日收盘仍未触及过 +1R 则离场；</li>
 *   <li>吊灯止损的 ATR 用整段序列的 Wilder 递推（futu-trader 按每天的 600 天窗口重算，两者差异很小）；</li>
 *   <li>减半仓（只在对照变体里开）：盘中首次触及 +1R 时一半按 +1R 成交，跳空高开越过 +1R 按开盘成交（futu-trader 一律按 +1R）；</li>
 *   <li>不计佣金、滑点、融资；不加仓；样本末尾仍未离场的记为未平仓，不计入 R 统计（浮盈计为零）。</li>
 * </ol>
 * 价格序列必须是同一口径（整段用同一个判定日基准换算），止损与 +1R 由调用方换算到这个口径。
 */
public final class PaperTrade {

    private PaperTrade() {
    }

    public record Rules(boolean halfAtPlusOneR, int chandelierPeriod, double chandelierAtrMultiple, int timeStopDays) {
        public static Rules of(SentinelThresholds th) {
            return new Rules(false, th.chandelierPeriod(), th.chandelierAtrMultiple(), th.timeStopDays());
        }

        public Rules withHalfAtPlusOneR() {
            return new Rules(true, chandelierPeriod, chandelierAtrMultiple, timeStopDays);
        }
    }

    public enum ExitReason {
        STOP, CHANDELIER, TIME, OPEN
    }

    /**
     * @param r      已实现盈亏 ÷ R（减半仓时按两半加权）；未平仓为 null
     * @param mfeR   持有期最大浮盈 ÷ R（按盘中最高）
     * @param maeR   持有期最大浮亏 ÷ R（按盘中最低，负数）
     */
    public record Result(LocalDate signalDate, LocalDate entryDate, double entry, double stop, double plusOneR,
                         boolean touchedPlusOneR, LocalDate exitDate, Double exit, ExitReason reason, Double r,
                         double mfeR, double maeR, int barsHeld) {
    }

    /**
     * @param bars   同一口径的日 K，升序
     * @param atr    与 bars 对齐的 ATR14
     * @param signal 判定日下标
     * @return 判定日之后没有 K 线（还没法入场）时返回 null
     */
    public static Result simulate(List<SignalBar> bars, double[] atr, int signal, double stop, Rules rules) {
        if (signal + 1 >= bars.size()) {
            return null;
        }
        double close = bars.get(signal).close();
        double risk = close - stop;
        if (!(risk > 0)) {
            throw new IllegalArgumentException("止损必须低于判定日收盘");
        }
        double target = close + risk;
        int entryIndex = signal + 1;
        double entry = bars.get(entryIndex).open();
        boolean touched = false;
        double halfExit = Double.NaN;
        double highest = Double.NEGATIVE_INFINITY;
        double lowest = Double.POSITIVE_INFINITY;
        for (int i = entryIndex; i < bars.size(); i++) {
            SignalBar b = bars.get(i);
            highest = Math.max(highest, b.high());
            lowest = Math.min(lowest, b.low());
            if (!touched && b.high() >= target) {
                touched = true;
                if (rules.halfAtPlusOneR()) {
                    halfExit = Math.max(target, b.open());    // 跳空高开越过 +1R 时按开盘成交
                }
            }
            double activeStop = stop;
            ExitReason stopReason = ExitReason.STOP;
            if (touched && !Double.isNaN(atr[i])) {
                double hh = Double.NEGATIVE_INFINITY;
                for (int k = Math.max(0, i - rules.chandelierPeriod() + 1); k <= i; k++) {
                    hh = Math.max(hh, bars.get(k).high());
                }
                double chandelier = hh - rules.chandelierAtrMultiple() * atr[i];
                if (chandelier > activeStop) {
                    activeStop = chandelier;
                    stopReason = ExitReason.CHANDELIER;
                }
            }
            ExitReason reason = null;
            if (b.close() <= activeStop) {
                reason = stopReason;
            } else if (!touched && i - entryIndex >= rules.timeStopDays()) {
                reason = ExitReason.TIME;
            }
            if (reason != null) {
                double pnl = Double.isNaN(halfExit)
                        ? b.close() - entry
                        : 0.5 * (halfExit - entry) + 0.5 * (b.close() - entry);
                return new Result(bars.get(signal).date(), bars.get(entryIndex).date(), entry, stop,
                        target, touched, b.date(), b.close(), reason, pnl / risk,
                        (highest - entry) / risk, (lowest - entry) / risk, i - entryIndex + 1);
            }
        }
        return new Result(bars.get(signal).date(), bars.get(entryIndex).date(), entry, stop, target, touched,
                null, null, ExitReason.OPEN, null, (highest - entry) / risk, (lowest - entry) / risk,
                bars.size() - entryIndex);
    }
}
