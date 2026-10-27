package org.jdkxx.trader.domain.selection;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.NavigableSet;
import java.util.TreeSet;

/**
 * 季报的可用日（§25.5，2026-10-12 用户认可的修订）：
 * <ol>
 *   <li><b>按日期就近匹配</b>公告日：期末后 {@value #MATCH_WINDOW_DAYS} 天内的第一个公告交易日。
 *       不按期别文本——富途财报接口与公告日接口的期别标签对不上（1~2 月财年的零售商差一个财年、FERG 改财年后公告日早于期末）。
 *       75 天小于下一季最早的公告时点（约 104 天），某季缺公告日时不会错配到下一季；</li>
 *   <li>没匹配上（2021 年以前，或富途列表中间缺的期）兜底取 <b>max(法定期限, 该标的已观测最大滞后)</b>：
 *       法定期限 = 期末加 40 天（财年第四季加 60 天），落在非交易日顺延到下一交易日。
 *       6.1 实测 58 期真实公告晚于法定期限，只用规则会偏早（偷看）。</li>
 * </ol>
 */
public final class QuarterAvailability {

    public static final int MATCH_WINDOW_DAYS = 75;
    public static final int STATUTORY_DAYS = 40;
    public static final int STATUTORY_DAYS_Q4 = 60;

    private QuarterAvailability() {
    }

    /**
     * @param quarters    同一标的的季报（期末已是真实期末）
     * @param pubDates    该标的全部公告交易日（不分期别）
     * @param tradingDays 交易日历；为空或覆盖不到时按周末顺延
     */
    public static List<QuarterFacts> resolve(List<QuarterFacts> quarters, Collection<LocalDate> pubDates,
                                             NavigableSet<LocalDate> tradingDays) {
        List<Period> periods = quarters.stream().map(q -> new Period(q.periodEnd(), q.fiscalQ4())).toList();
        List<LocalDate> available = availableDates(periods, pubDates, tradingDays);
        List<QuarterFacts> out = new ArrayList<>(quarters.size());
        for (int i = 0; i < quarters.size(); i++) {
            out.add(quarters.get(i).withAvailableOn(available.get(i)));
        }
        return out;
    }

    /** 一期报表：真实期末与是否财年第四季（年报按第四季的期限）。 */
    public record Period(LocalDate periodEnd, boolean fiscalQ4) {
    }

    /** 与 {@link #resolve} 同一口径，只给日期：结果与 {@code periods} 一一对应（入场信号的模型输入也用它判断报表在判定日是否已公布）。 */
    public static List<LocalDate> availableDates(List<Period> periods, Collection<LocalDate> pubDates,
                                                 NavigableSet<LocalDate> tradingDays) {
        TreeSet<LocalDate> pubs = new TreeSet<>(pubDates);
        LocalDate[] matched = new LocalDate[periods.size()];
        long maxLag = 0;
        for (int i = 0; i < periods.size(); i++) {
            LocalDate end = periods.get(i).periodEnd();
            LocalDate p = pubs.higher(end);
            if (p != null && !p.isAfter(end.plusDays(MATCH_WINDOW_DAYS))) {
                matched[i] = p;
                maxLag = Math.max(maxLag, ChronoUnit.DAYS.between(end, p));
            }
        }
        List<LocalDate> out = new ArrayList<>(periods.size());
        for (int i = 0; i < periods.size(); i++) {
            Period q = periods.get(i);
            LocalDate available = matched[i];
            if (available == null) {
                LocalDate statutory = q.periodEnd().plusDays(q.fiscalQ4() ? STATUTORY_DAYS_Q4 : STATUTORY_DAYS);
                LocalDate observed = q.periodEnd().plusDays(maxLag);
                available = rollToTradingDay(statutory.isAfter(observed) ? statutory : observed, tradingDays);
            }
            out.add(available);
        }
        return out;
    }

    /** 当天是交易日就是当天，否则顺延；日历覆盖不到的日子按周末顺延。 */
    static LocalDate rollToTradingDay(LocalDate d, NavigableSet<LocalDate> tradingDays) {
        if (tradingDays != null && !tradingDays.isEmpty() && !d.isAfter(tradingDays.last())) {
            LocalDate t = tradingDays.ceiling(d);
            if (t != null) {
                return t;
            }
        }
        LocalDate x = d;
        while (x.getDayOfWeek() == DayOfWeek.SATURDAY || x.getDayOfWeek() == DayOfWeek.SUNDAY) {
            x = x.plusDays(1);
        }
        return x;
    }
}
