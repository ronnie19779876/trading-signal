package org.jdkxx.trader.domain.holding;

import org.jdkxx.trader.domain.signal.SentinelThresholds;
import org.jdkxx.trader.domain.signal.SignalBar;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TrendReviewTest {

    private static final HoldingRules RULES = HoldingRules.of(SentinelThresholds.V1, 50, 3, true);
    private static final LocalDate START = LocalDate.of(2020, 1, 1);

    /** 收盘价序列造 K 线：高低价 ±0.5，开盘 = 前收。 */
    private static List<SignalBar> bars(double... closes) {
        List<SignalBar> out = new ArrayList<>();
        for (int i = 0; i < closes.length; i++) {
            double c = closes[i];
            double o = i == 0 ? c : closes[i - 1];
            out.add(new SignalBar(START.plusDays(i), o, Math.max(o, c) + 0.5, Math.min(o, c) - 0.5, c, 1000));
        }
        return out;
    }

    /** n 天从 start 线性涨到 end。 */
    private static double[] ramp(int n, double start, double end) {
        double[] x = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = start + (end - start) * i / (n - 1);
        }
        return x;
    }

    private static double[] concat(double[]... parts) {
        int n = 0;
        for (double[] p : parts) {
            n += p.length;
        }
        double[] out = new double[n];
        int k = 0;
        for (double[] p : parts) {
            System.arraycopy(p, 0, out, k, p.length);
            k += p.length;
        }
        return out;
    }

    @Test
    void 样本不足时不判定() {
        List<TrendReview.Day> days = TrendReview.evaluate(bars(ramp(RULES.minBars() - 1, 100, 200)), RULES);

        assertThat(days).allMatch(d -> d.state() == TrendReview.State.UNAVAILABLE && d.active().isEmpty());
    }

    @Test
    void 稳步上涨是上升强势且没有复核条件() {
        List<TrendReview.Day> days = TrendReview.evaluate(bars(ramp(300, 100, 200)), RULES);
        TrendReview.Day last = days.getLast();

        assertThat(last.state()).isEqualTo(TrendReview.State.UP_STRONG);
        assertThat(last.active()).isEmpty();
    }

    /** 破位要连续 3 天收盘在 200 日均线之下：第 1、2 天是待确认，第 3 天才算破位，而且「刚刚成立」只在第 3 天。 */
    @Test
    void 跌破长均线连续三天才算破位确认() {
        double[] up = ramp(300, 100, 200);
        // 之后一口气跌到 120（明显低于 200 日均线，约 150 上下）并维持
        double[] series = concat(up, new double[] {120, 120, 120, 120, 120});
        List<TrendReview.Day> days = TrendReview.evaluate(bars(series), RULES);
        int t = up.length;

        assertThat(days.get(t).state()).isEqualTo(TrendReview.State.BELOW);
        assertThat(days.get(t + 1).state()).isEqualTo(TrendReview.State.BELOW);
        assertThat(days.get(t + 2).state()).isEqualTo(TrendReview.State.BROKEN);
        assertThat(days.get(t + 2).belowDays()).isEqualTo(3);
        assertThat(days.get(t + 2).edges()).contains(TrendReview.Trigger.BREAK_CONFIRMED);
        assertThat(days.get(t + 3).active()).contains(TrendReview.Trigger.BREAK_CONFIRMED);
        assertThat(days.get(t + 3).edges()).doesNotContain(TrendReview.Trigger.BREAK_CONFIRMED);
    }

    @Test
    void 回到长均线之上就不再计连续天数() {
        double[] up = ramp(300, 100, 200);
        double[] series = concat(up, new double[] {120, 120, 250, 120});
        List<TrendReview.Day> days = TrendReview.evaluate(bars(series), RULES);
        int t = up.length;

        assertThat(days.get(t + 2).belowDays()).isZero();
        assertThat(days.get(t + 3).belowDays()).isEqualTo(1);
        assertThat(days.get(t + 3).state()).isEqualTo(TrendReview.State.BELOW);
    }

    /** 50 日均线下穿 200 日均线：刚刚成立那天是一次「edge」，之后只是 active。 */
    @Test
    void 死叉只在下穿当天算刚刚成立() {
        double[] series = concat(ramp(300, 100, 200), ramp(200, 199, 80));
        List<TrendReview.Day> days = TrendReview.evaluate(bars(series), RULES);
        long edges = days.stream().filter(d -> d.edges().contains(TrendReview.Trigger.DEATH_CROSS)).count();
        int first = -1;
        for (int i = 0; i < days.size(); i++) {
            if (days.get(i).active().contains(TrendReview.Trigger.DEATH_CROSS)) {
                first = i;
                break;
            }
        }

        assertThat(edges).isEqualTo(1);
        assertThat(first).isPositive();
        assertThat(days.get(first).smaShort()).isLessThan(days.get(first).smaLong());
        assertThat(days.get(first - 1).smaShort()).isGreaterThanOrEqualTo(days.get(first - 1).smaLong());
        assertThat(days.get(first).edges()).contains(TrendReview.Trigger.DEATH_CROSS);
    }

    @Test
    void 关掉死叉就不作为复核条件() {
        HoldingRules noCross = HoldingRules.of(SentinelThresholds.V1, 50, 3, false);
        List<TrendReview.Day> days = TrendReview.evaluate(bars(concat(ramp(300, 100, 200), ramp(200, 199, 80))), noCross);

        assertThat(days).noneMatch(d -> d.active().contains(TrendReview.Trigger.DEATH_CROSS));
    }

    /** 吊灯线 = 最近 22 根最高价 − 3 × ATR14；收盘不高于它就成立。 */
    @Test
    void 收盘跌破吊灯线() {
        double[] up = ramp(300, 100, 200);
        List<TrendReview.Day> days = TrendReview.evaluate(bars(concat(up, new double[] {150})), RULES);
        TrendReview.Day d = days.getLast();

        assertThat(d.chandelier()).isGreaterThan(150);
        assertThat(d.active()).contains(TrendReview.Trigger.CHANDELIER_BREACH);
        assertThat(days.get(up.length - 1).active()).doesNotContain(TrendReview.Trigger.CHANDELIER_BREACH);
    }

    /** 不偷看：每一天的判定与只用截至那天的数据算出来的完全相同。 */
    @Test
    void 每天只用当天及以前的数据() {
        double[] series = concat(ramp(300, 100, 200), ramp(120, 199, 90), ramp(80, 91, 160));
        List<TrendReview.Day> full = TrendReview.evaluate(bars(series), RULES);
        for (int n : new int[] {260, 320, 360, 420, 499}) {
            double[] prefix = java.util.Arrays.copyOf(series, n);
            TrendReview.Day cut = TrendReview.evaluate(bars(prefix), RULES).getLast();
            TrendReview.Day same = full.get(n - 1);
            assertThat(cut.state()).isEqualTo(same.state());
            assertThat(cut.active()).isEqualTo(same.active());
            assertThat(cut.edges()).isEqualTo(same.edges());
        }
    }
}
