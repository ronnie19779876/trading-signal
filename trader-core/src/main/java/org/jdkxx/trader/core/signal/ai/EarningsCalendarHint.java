package org.jdkxx.trader.core.signal.ai;

import org.jdkxx.trader.domain.selection.EarningsEvent;
import org.jdkxx.trader.domain.selection.EarningsTiming;
import org.jdkxx.trader.domain.selection.QuarterAvailability;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;

/**
 * 模型输入里的 {@code calendarHint}（3.4.8）：以券商的财报公告日为准，说清最近一次财报是不是已经反映在判定日收盘价里、
 * 下一次在判定日之后第几个交易日。纯函数，键序固定（输入哈希要稳定）。
 *
 * <p>起因（2026-10-27 生产）：原先只按「库里最近一季的期末 + 91 天 + 25~45 天」推算，下限截在 0。财报季里新一季的报表
 * 要过几天才进库，<b>刚公布</b>财报的标的被推算成「0~16 天内就要公布」，7 条否决里 5 条（PFG 10-26 盘后、CNP / HLT / ITW / JCI
 * 10-27 盘前）的前提是把已经过去的事件当成了即将到来。
 *
 * <p>归属按时段：入场是判定日之后的第一个开盘，所以判定日当天<b>盘前、盘中</b>公布的算已反映；<b>盘后与时段未知</b>的算尚未反映
 * （时段未知按保守处理，并在说明里写明）。
 */
public final class EarningsCalendarHint {

    public static final String BROKER_CALENDAR = "BROKER_CALENDAR";
    public static final String PERIOD_END_RULE = "PERIOD_END_RULE";
    /** 美股季报节奏：下一次约在上一次之后 91 天 */
    static final int QUARTER_DAYS = 91;

    private EarningsCalendarHint() {
    }

    /**
     * @param events           该标的全部公告日，从早到晚（可为空：ETF、选股范围之外）
     * @param latestQuarterEnd 输入里 {@code financials.quarters[0].periodEnd} 的值（库内期末，真实期末是它加 1 天）；没有季报为 null
     * @param tradingDays      交易日历；覆盖不到的日子按工作日数
     * @param pastDate         判定日早于今天 7 天以上（手工分析过去的日子）
     * @param caveats          追加数据缺口说明
     */
    public static Map<String, Object> build(List<EarningsEvent> events, LocalDate asOf, LocalDate latestQuarterEnd,
                                            NavigableSet<LocalDate> tradingDays, boolean pastDate, List<String> caveats) {
        EarningsEvent last = null;
        EarningsEvent next = null;
        for (EarningsEvent e : events) {
            if (reflected(e, asOf)) {
                last = e;
            } else if (next == null) {
                next = e;
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        boolean broker = !events.isEmpty();
        m.put("source", broker ? BROKER_CALENDAR : PERIOD_END_RULE);
        m.put("lastAnnounced", last == null ? null : event(last, "tradingDaysAgo", tradingDaysBetween(last.pubDate(), asOf, tradingDays)));
        m.put("nextScheduled", next == null ? null : event(next, "tradingDaysAhead", tradingDaysBetween(asOf, next.pubDate(), tradingDays)));
        Long since = latestQuarterEnd == null ? null : ChronoUnit.DAYS.between(latestQuarterEnd, asOf);
        m.put("latestQuarterEnd", latestQuarterEnd == null ? null : latestQuarterEnd.toString());
        m.put("daysSinceLatestQuarterEnd", since);
        Boolean inFinancials = null;
        if (last != null && latestQuarterEnd != null) {
            // 「是不是同一期」按日期判（公告落在真实期末后 75 天内），不比期别文本：富途两个接口的期别标签对不上（§25.5）
            inFinancials = !last.pubDate().isAfter(latestQuarterEnd.plusDays(1 + QuarterAvailability.MATCH_WINDOW_DAYS));
            if (!inFinancials) {
                caveats.add("最近一次财报（" + last.periodText() + "，" + last.pubDate() + " 公布）的报表尚未入库：financials 的最新一季期末为 "
                        + latestQuarterEnd + "，不含该期");
            }
        }
        m.put("latestAnnouncedInFinancials", inFinancials);
        m.put("note", broker ? brokerNote(last, next, asOf, tradingDays) : ruleNote(since));
        if (broker && pastDate) {
            caveats.add("判定日早于今天：公告日取自现在的券商列表，nextScheduled 是后来实际（或现在预定）的日期，判定日当时未必已经排期");
        }
        return m;
    }

    /** 判定日收盘价是否已反映这次公告：之前的日子，或判定日当天盘前 / 盘中。 */
    static boolean reflected(EarningsEvent e, LocalDate asOf) {
        if (e.pubDate().isBefore(asOf)) {
            return true;
        }
        return e.pubDate().equals(asOf) && (e.timing() == EarningsTiming.PRE_MARKET || e.timing() == EarningsTiming.DURING_MARKET);
    }

    private static Map<String, Object> event(EarningsEvent e, String daysKey, int days) {
        Map<String, Object> x = new LinkedHashMap<>();
        x.put("period", e.periodText());
        x.put("date", e.pubDate().toString());
        x.put("timing", e.timing().name());
        x.put(daysKey, days);
        return x;
    }

    /** (from, to] 里的交易日数；日历覆盖不到的部分按工作日数（未来的假日会多算，只用于提示）。 */
    static int tradingDaysBetween(LocalDate from, LocalDate to, NavigableSet<LocalDate> tradingDays) {
        if (!to.isAfter(from)) {
            return 0;
        }
        LocalDate covered = tradingDays.isEmpty() ? from : tradingDays.last();
        int n = 0;
        if (covered.isAfter(from)) {
            LocalDate end = to.isBefore(covered) ? to : covered;
            n += tradingDays.subSet(from, false, end, true).size();
        }
        for (LocalDate d = (covered.isAfter(from) ? covered : from).plusDays(1); !d.isAfter(to); d = d.plusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                n++;
            }
        }
        return n;
    }

    private static String brokerNote(EarningsEvent last, EarningsEvent next, LocalDate asOf, NavigableSet<LocalDate> tradingDays) {
        StringBuilder s = new StringBuilder("财报日期取自券商公告日列表；入场在判定日之后的第一个开盘。");
        if (last != null) {
            int ago = tradingDaysBetween(last.pubDate(), asOf, tradingDays);
            s.append("最近一次财报（").append(last.periodText()).append("）已于 ").append(last.pubDate()).append(timingText(last.timing()))
                    .append(ago == 0 ? "公布，就在判定日当天" : "公布，在判定日之前 " + ago + " 个交易日")
                    .append("，判定日收盘价已经反映，这次事件已经过去。");
        }
        if (next == null) {
            s.append("券商尚未给出下一次财报的预定日期");
            if (last != null) {
                s.append("；按季度节奏，大约在上次公布后 ").append(QUARTER_DAYS).append(" 天前后（约 ")
                        .append(last.pubDate().plusDays(QUARTER_DAYS)).append("），这是估计");
            }
            s.append("。");
        } else if (next.pubDate().equals(asOf)) {
            s.append("下一次财报（").append(next.periodText()).append("）在判定日当天");
            s.append(next.timing() == EarningsTiming.UNKNOWN
                    ? "公布、时段未知：可能已在盘前公布，也可能在盘后，按尚未反映处理，最晚在入场前公布。"
                    : "盘后公布：判定日收盘价尚未反映，入场前就会公布。");
        } else {
            s.append("下一次财报（").append(next.periodText()).append("）预定 ").append(next.pubDate()).append(timingText(next.timing()))
                    .append("公布，在判定日之后第 ").append(tradingDaysBetween(asOf, next.pubDate(), tradingDays)).append(" 个交易日。");
        }
        return s.toString();
    }

    private static String ruleNote(Long since) {
        if (since == null) {
            return "没有该标的的财报公告日记录，也没有季报，无法判断财报时间。";
        }
        long lo = QUARTER_DAYS + 25 - since;
        long hi = QUARTER_DAYS + 45 - since;
        String head = "没有该标的的财报公告日记录，只能按期末推算：美股季报通常在期末后约 25~45 天公布、下一期期末约在最近期末后 "
                + QUARTER_DAYS + " 天；距最近期末 " + since + " 天，";
        if (hi < 0) {
            return head + "按推算下一次本应已经公布，但没有记录，无法判断下一次财报时间。";
        }
        return head + "据此估计下一次财报大约在判定日后 " + Math.max(0, lo) + "~" + hi + " 天（推算，不是券商日期）。";
    }

    private static String timingText(EarningsTiming t) {
        return switch (t) {
            case PRE_MARKET -> "盘前";
            case DURING_MARKET -> "盘中";
            case AFTER_MARKET -> "盘后";
            case UNKNOWN -> "（时段未知）";
        };
    }
}
