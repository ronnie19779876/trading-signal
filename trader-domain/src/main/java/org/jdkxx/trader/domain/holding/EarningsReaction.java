package org.jdkxx.trader.domain.holding;

import org.jdkxx.trader.domain.selection.EarningsEvent;
import org.jdkxx.trader.domain.selection.EarningsTiming;
import org.jdkxx.trader.domain.signal.SignalBar;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 财报后的价格反应（3.5.0，ARCHITECTURE §29）。纯函数，输入结构口径日 K（跨拆股连续，比值不受拆股影响）与券商公告日。
 *
 * <p>反应日按公告时段：
 * <ul>
 *   <li>盘前 / 盘中：公告当天相对前一交易日收盘；</li>
 *   <li>盘后：公告后第一个交易日相对公告日收盘；</li>
 *   <li>时段未知：按盘前（当天对前收）与按盘后（次日对公告日收盘）各算一次，<b>取波动更大的那一天</b>。
 *       起初用两天窗口（次日收盘对公告前一日收盘），开发实例核对 GOOG 时发现它会稀释：2022/Q3 公告当天先涨 1.9%，
 *       两天合并只有 −7.91%，次日实际 −9.63%——两天窗口并不保守。
 *       取大的一天在幅度上偏保守，但可能把同期别的新闻算进来（GOOG 2022/Q4：取到公告当天 +7.27%，实际反应是次日 −3.29%）。</li>
 * </ul>
 * 反应日还没有 K 线（未来的、或刚公布还没收盘的）不计入。
 */
public final class EarningsReaction {

    /** 大波动的门槛：|当日涨跌| ≥ 5% */
    public static final double BIG_MOVE = 0.05;

    /**
     * @param gap  开盘相对基准收盘
     * @param move 收盘相对基准收盘
     */
    public record Sample(String period, LocalDate pubDate, EarningsTiming timing, LocalDate reactionDate, Double gap, double move) {
    }

    /**
     * @param maxDrop  最大跌幅，取正数（从来没跌过为 0）
     * @param worstGap 最差的开盘跳空（没有可用跳空时为 null）
     */
    public record Stats(int samples, double meanAbsMove, double medianAbsMove, double maxDrop, double maxRise,
                        int bigMoves, int ups, int downs, Double worstGap) {
    }

    private EarningsReaction() {
    }

    /** 判定日收盘价是否已经反映这次公告：之前的日子，或判定日当天盘前 / 盘中（与模型输入的 calendarHint 同一口径）。 */
    public static boolean reflected(EarningsEvent e, LocalDate asOf) {
        if (e.pubDate().isBefore(asOf)) {
            return true;
        }
        return e.pubDate().equals(asOf) && (e.timing() == EarningsTiming.PRE_MARKET || e.timing() == EarningsTiming.DURING_MARKET);
    }

    public static List<Sample> samples(List<SignalBar> bars, List<EarningsEvent> events) {
        List<Sample> out = new ArrayList<>();
        for (EarningsEvent e : events) {
            Sample s = sample(bars, e);
            if (s != null) {
                out.add(s);
            }
        }
        return out;
    }

    static Sample sample(List<SignalBar> bars, EarningsEvent e) {
        int before = -1;   // 公告日之前最后一根
        int on = -1;       // 公告日当天
        int after = -1;    // 公告日之后第一根
        for (int i = 0; i < bars.size(); i++) {
            LocalDate d = bars.get(i).date();
            if (d.isBefore(e.pubDate())) {
                before = i;
            } else if (d.equals(e.pubDate())) {
                on = i;
            } else {
                after = i;
                break;
            }
        }
        switch (e.timing()) {
            case PRE_MARKET, DURING_MARKET -> {
                // 公告日不是交易日（富途给的是生效交易日，一般不会）：落到下一个交易日
                int react = on >= 0 ? on : after;
                if (react < 0 || before < 0) {
                    return null;
                }
                SignalBar base = bars.get(before);
                SignalBar r = bars.get(react);
                return new Sample(e.periodText(), e.pubDate(), e.timing(), r.date(), r.open() / base.close() - 1, r.close() / base.close() - 1);
            }
            case AFTER_MARKET -> {
                int baseIdx = on >= 0 ? on : before;
                if (after < 0 || baseIdx < 0) {
                    return null;
                }
                SignalBar base = bars.get(baseIdx);
                SignalBar r = bars.get(after);
                return new Sample(e.periodText(), e.pubDate(), e.timing(), r.date(), r.open() / base.close() - 1, r.close() / base.close() - 1);
            }
            default -> {
                // 时段未知：两种口径都算，取波动更大的一天（真正的反应日通常就是动得多的那天）
                Sample pre = on >= 0 && before >= 0 ? new Sample(e.periodText(), e.pubDate(), e.timing(), bars.get(on).date(),
                        bars.get(on).open() / bars.get(before).close() - 1, bars.get(on).close() / bars.get(before).close() - 1) : null;
                int baseIdx = on >= 0 ? on : before;
                Sample post = after >= 0 && baseIdx >= 0 ? new Sample(e.periodText(), e.pubDate(), e.timing(), bars.get(after).date(),
                        bars.get(after).open() / bars.get(baseIdx).close() - 1, bars.get(after).close() / bars.get(baseIdx).close() - 1) : null;
                if (pre == null || post == null) {
                    // 次日还没有 K 线：还不知道哪天是反应日，不计入
                    return null;
                }
                return Math.abs(post.move()) >= Math.abs(pre.move()) ? post : pre;
            }
        }
    }

    /** 没有样本返回 null。 */
    public static Stats stats(List<Sample> samples) {
        if (samples.isEmpty()) {
            return null;
        }
        double[] abs = samples.stream().mapToDouble(s -> Math.abs(s.move())).sorted().toArray();
        double mean = 0;
        for (double a : abs) {
            mean += a;
        }
        mean /= abs.length;
        double median = abs.length % 2 == 1 ? abs[abs.length / 2] : (abs[abs.length / 2 - 1] + abs[abs.length / 2]) / 2;
        double min = samples.stream().mapToDouble(Sample::move).min().orElseThrow();
        double max = samples.stream().mapToDouble(Sample::move).max().orElseThrow();
        int big = (int) samples.stream().filter(s -> Math.abs(s.move()) >= BIG_MOVE).count();
        int ups = (int) samples.stream().filter(s -> s.move() > 0).count();
        int downs = (int) samples.stream().filter(s -> s.move() < 0).count();
        Double worstGap = samples.stream().map(Sample::gap).filter(g -> g != null).min(Double::compare).orElse(null);
        return new Stats(samples.size(), mean, median, Math.max(0, -min), Math.max(0, max), big, ups, downs, worstGap);
    }
}
