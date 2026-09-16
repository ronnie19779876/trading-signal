package org.jdkxx.trader.domain.signal;

import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.RehabFactor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 判定前的数据层检查与口径换算（纯函数），顺序：
 * <ol>
 *   <li>截取窗口：判定日往前 {@code windowCalendarDays} 自然日；</li>
 *   <li>剔除交易日历之外的 K 线：富途在美股假日留过脏 K 线（SPY 2011-07-04 等，最低价异常），
 *       留着会被识别成分形低点、凭空造出支撑区；</li>
 *   <li>判定日没有 K 线 → SKIPPED_STALE_DATA；</li>
 *   <li>从首根 K 线到判定日，对照日历缺的交易日（停牌空 K 也算缺）超过容忍度 → SKIPPED_DATA_GAP。
 *       富途的 SPY 历史缺过 26 个交易日，而且前收与缺口自洽，连续性检查查不出来，只能对日历；</li>
 *   <li>结构口径换算（{@link StructuralAdjustment}），算不出来 → SKIPPED_CORPORATE_ACTION。</li>
 * </ol>
 */
public final class SignalInputs {

    private SignalInputs() {
    }

    /**
     * @param skipped 不予判定时的结果，此时 bars 为空
     */
    public record Prepared(List<SignalBar> bars, SentinelEvaluation skipped,
                           List<LocalDate> droppedNonTradingDays, List<LocalDate> missingTradingDays) {
    }

    /**
     * @param raw         不复权日 K，可以比窗口长（回放时一次取出多年）
     * @param tradingDays 交易日历，至少覆盖窗口
     */
    public static Prepared prepare(List<DailyBar> raw, List<RehabFactor> factors, Collection<LocalDate> tradingDays,
                                   LocalDate asOf, SentinelThresholds th) {
        Set<LocalDate> calendar = tradingDays instanceof Set<LocalDate> s ? s : new HashSet<>(tradingDays);
        if (!calendar.contains(asOf)) {
            throw new IllegalArgumentException(asOf + " 不是交易日");
        }
        LocalDate from = asOf.minusDays(th.windowCalendarDays());
        List<DailyBar> window = new ArrayList<>();
        List<LocalDate> dropped = new ArrayList<>();
        for (DailyBar b : raw) {
            if (b.tradeDate().isBefore(from) || b.tradeDate().isAfter(asOf)) {
                continue;
            }
            if (calendar.contains(b.tradeDate())) {
                window.add(b);
            } else {
                dropped.add(b.tradeDate());
            }
        }
        if (window.isEmpty() || !window.get(window.size() - 1).tradeDate().equals(asOf)) {
            String latest = window.isEmpty() ? "窗口内没有 K 线" : "最新 K 线是 " + window.get(window.size() - 1).tradeDate();
            return skipped(th, asOf, SentinelEvaluation.Status.SKIPPED_STALE_DATA, "判定日没有 K 线，" + latest, dropped, List.of());
        }

        Set<LocalDate> present = new HashSet<>();
        window.stream().filter(b -> !b.blank()).forEach(b -> present.add(b.tradeDate()));
        LocalDate first = window.get(0).tradeDate();
        List<LocalDate> missing = new TreeSet<>(calendar).subSet(first, true, asOf, true).stream()
                .filter(d -> !present.contains(d))
                .toList();
        if (missing.size() > th.maxMissingTradingDays()) {
            return skipped(th, asOf, SentinelEvaluation.Status.SKIPPED_DATA_GAP, "窗口内缺 " + missing.size() + " 个交易日（容忍 "
                    + th.maxMissingTradingDays() + "），最早 " + missing.get(0) + "、最晚 " + missing.get(missing.size() - 1),
                    dropped, missing);
        }

        StructuralAdjustment.Result adjusted = StructuralAdjustment.apply(window, factors, asOf);
        if (!adjusted.ok()) {
            return skipped(th, asOf, SentinelEvaluation.Status.SKIPPED_CORPORATE_ACTION, adjusted.problem(), dropped, missing);
        }
        return new Prepared(adjusted.bars(), null, List.copyOf(dropped), List.copyOf(missing));
    }

    /** 数据层检查 + 四门判定。 */
    public static SentinelEvaluation judge(List<DailyBar> raw, List<RehabFactor> factors, Collection<LocalDate> tradingDays,
                                           LocalDate asOf, SentinelThresholds th) {
        Prepared p = prepare(raw, factors, tradingDays, asOf, th);
        return p.skipped() != null ? p.skipped() : SentinelEvaluator.evaluate(p.bars(), asOf, th);
    }

    private static Prepared skipped(SentinelThresholds th, LocalDate asOf, SentinelEvaluation.Status status, String detail,
                                    List<LocalDate> dropped, List<LocalDate> missing) {
        return new Prepared(List.of(), SentinelEvaluation.skipped(th.version(), asOf, status, detail),
                List.copyOf(dropped), List.copyOf(missing));
    }
}
