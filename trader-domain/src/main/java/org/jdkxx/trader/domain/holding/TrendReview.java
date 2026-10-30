package org.jdkxx.trader.domain.holding;

import org.jdkxx.trader.domain.signal.Indicators;
import org.jdkxx.trader.domain.signal.SignalBar;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 逐日的趋势状态与减仓复核条件（3.5.0，ARCHITECTURE §29）。纯函数：输入结构口径的日 K（跨拆股连续），每一天只用当天及以前的数据。
 *
 * <p><b>这不是买卖信号。</b>第 4 期回测里，含「收盘在 200 日均线之上且均线上升」的入场判据与「同标的同年份任意一天入场」相比没有显著差别
 * （§18.6），均线不能证明有择时价值；这里只把用户自己定的纪律条件逐条检查出来，有没有提示价值由 §29 的历史验证回答。
 */
public final class TrendReview {

    /** 趋势状态。 */
    public enum State {
        /** 收盘 > 长均线且长均线上升（= 入场信号趋势门通过），并且收盘 > 短均线 */
        UP_STRONG("上升（强）"),
        /** 趋势门通过，但收盘 ≤ 短均线 */
        UP_WEAK("上升（走弱）"),
        /** 收盘 > 长均线，但长均线没有上升 */
        ABOVE_FLAT("均线走平"),
        /** 收盘 ≤ 长均线，连续天数还不到确认天数 */
        BELOW("跌破（待确认）"),
        /** 连续确认天数收盘 ≤ 长均线 */
        BROKEN("破位"),
        /** 样本不足 */
        UNAVAILABLE("样本不足");

        private final String label;

        State(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 减仓复核条件（价格类；财报超预算由面板另算）。 */
    public enum Trigger {
        /** 连续确认天数收盘 ≤ 长均线 */
        BREAK_CONFIRMED,
        /** 短均线 < 长均线（死叉之后的状态） */
        DEATH_CROSS,
        /** 收盘 ≤ 吊灯线（最近 N 根最高价 − k × ATR） */
        CHANDELIER_BREACH
    }

    /**
     * 一天的判定。均线与吊灯在样本不足时为 NaN。
     *
     * @param belowDays 截至当天连续收盘 ≤ 长均线的天数（高于时为 0）
     * @param active    当天成立的复核条件
     * @param edges     当天<b>刚刚</b>成立（前一天不成立）的复核条件——历史验证只取这些，避免同一段下跌重复计数
     */
    public record Day(LocalDate date, double close, double high, double smaShort, double smaLong, double smaLongPrior,
                      State state, int belowDays, double atr, double chandelier, Set<Trigger> active, Set<Trigger> edges) {
    }

    private TrendReview() {
    }

    public static List<Day> evaluate(List<SignalBar> bars, HoldingRules rules) {
        int n = bars.size();
        double[] close = bars.stream().mapToDouble(SignalBar::close).toArray();
        double[] high = bars.stream().mapToDouble(SignalBar::high).toArray();
        double[] low = bars.stream().mapToDouble(SignalBar::low).toArray();
        double[] smaS = Indicators.sma(close, rules.smaShort());
        double[] smaL = Indicators.sma(close, rules.smaLong());
        double[] atr = Indicators.wilderAtr(high, low, close, rules.atrPeriod());
        List<Day> out = new ArrayList<>(n);
        int below = 0;
        Set<Trigger> previous = EnumSet.noneOf(Trigger.class);
        for (int t = 0; t < n; t++) {
            double prior = t >= rules.slopeLookback() ? smaL[t - rules.slopeLookback()] : Double.NaN;
            boolean ready = !Double.isNaN(smaL[t]) && !Double.isNaN(prior) && !Double.isNaN(smaS[t]);
            if (!Double.isNaN(smaL[t])) {
                below = close[t] <= smaL[t] ? below + 1 : 0;
            }
            double hh = Double.NEGATIVE_INFINITY;
            for (int i = Math.max(0, t - rules.chandelierPeriod() + 1); i <= t; i++) {
                hh = Math.max(hh, high[i]);
            }
            double chandelier = t + 1 >= rules.chandelierPeriod() && !Double.isNaN(atr[t])
                    ? hh - rules.chandelierAtrMultiple() * atr[t] : Double.NaN;

            State state;
            Set<Trigger> active = EnumSet.noneOf(Trigger.class);
            if (!ready) {
                state = State.UNAVAILABLE;
            } else {
                boolean above = close[t] > smaL[t];
                boolean rising = smaL[t] > prior;
                if (above && rising) {
                    state = close[t] > smaS[t] ? State.UP_STRONG : State.UP_WEAK;
                } else if (above) {
                    state = State.ABOVE_FLAT;
                } else {
                    state = below >= rules.breakConfirmDays() ? State.BROKEN : State.BELOW;
                }
                if (state == State.BROKEN) {
                    active.add(Trigger.BREAK_CONFIRMED);
                }
                if (rules.deathCross() && smaS[t] < smaL[t]) {
                    active.add(Trigger.DEATH_CROSS);
                }
                if (!Double.isNaN(chandelier) && close[t] <= chandelier) {
                    active.add(Trigger.CHANDELIER_BREACH);
                }
            }
            Set<Trigger> edges = EnumSet.noneOf(Trigger.class);
            for (Trigger x : active) {
                // 第一天能判定时就成立的不算「刚刚成立」：不知道它之前成立了多久
                if (!previous.contains(x) && t > 0 && out.get(t - 1).state() != State.UNAVAILABLE) {
                    edges.add(x);
                }
            }
            out.add(new Day(bars.get(t).date(), close[t], high[t], smaS[t], smaL[t], prior, state, below, atr[t], chandelier,
                    Set.copyOf(active), Set.copyOf(edges)));
            previous = active;
        }
        return out;
    }
}
