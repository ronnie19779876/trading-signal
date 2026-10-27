package org.jdkxx.trader.core.signal.ai;

import org.jdkxx.trader.domain.FinancialReport;
import org.jdkxx.trader.domain.FinancialStatement;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.selection.EarningsEvent;
import org.jdkxx.trader.domain.selection.EarningsTiming;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/** 场景取自 2026-10-27 生产的 7 条否决（PFG、CNP、HLT、ITW、JCI 已公布被当成即将公布；APH 次日、WMB 4 个交易日后）。 */
class EarningsCalendarHintTest {

    private static final LocalDate AS_OF = d("2026-10-27");
    /** 按工作日造的日历（不扣假日），覆盖到 2026-11-06，之后不覆盖 */
    private static final TreeSet<LocalDate> CALENDAR = weekdays(d("2026-07-01"), d("2026-11-06"));

    private static LocalDate d(String s) {
        return LocalDate.parse(s);
    }

    private static TreeSet<LocalDate> weekdays(LocalDate from, LocalDate to) {
        TreeSet<LocalDate> out = new TreeSet<>();
        for (LocalDate x = from; !x.isAfter(to); x = x.plusDays(1)) {
            if (x.getDayOfWeek() != DayOfWeek.SATURDAY && x.getDayOfWeek() != DayOfWeek.SUNDAY) {
                out.add(x);
            }
        }
        return out;
    }

    private static EarningsEvent e(String period, String date, EarningsTiming timing) {
        return new EarningsEvent(period, 2026, d(date), null, timing, false);
    }

    private static Map<String, Object> hint(List<EarningsEvent> events, String latestQuarterEnd, List<String> caveats) {
        return EarningsCalendarHint.build(events, AS_OF, latestQuarterEnd == null ? null : d(latestQuarterEnd), CALENDAR, false, caveats);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sub(Map<String, Object> m, String key) {
        return (Map<String, Object>) m.get(key);
    }

    @Test
    void 判定日当天盘前公布算已反映_HLT() {
        Map<String, Object> m = hint(List.of(e("2026/Q2", "2026-07-28", EarningsTiming.PRE_MARKET),
                e("2026/Q3", "2026-10-27", EarningsTiming.PRE_MARKET)), "2026-06-29", new ArrayList<>());

        assertThat(m.get("source")).isEqualTo("BROKER_CALENDAR");
        assertThat(sub(m, "lastAnnounced")).containsEntry("period", "2026/Q3").containsEntry("tradingDaysAgo", 0);
        assertThat(m.get("nextScheduled")).isNull();
        assertThat((String) m.get("note")).contains("就在判定日当天").contains("已经过去").contains("券商尚未给出下一次").contains("2027-01-26").doesNotContain("0~");
    }

    @Test
    void 前一天盘后公布算已反映_PFG() {
        Map<String, Object> m = hint(List.of(e("2026/Q3", "2026-10-26", EarningsTiming.AFTER_MARKET)), "2026-06-29", new ArrayList<>());

        assertThat(sub(m, "lastAnnounced")).containsEntry("period", "2026/Q3").containsEntry("tradingDaysAgo", 1);
        assertThat(m.get("nextScheduled")).isNull();
    }

    @Test
    void 判定日当天盘后公布算尚未反映_入场前就会公布() {
        Map<String, Object> m = hint(List.of(e("2026/Q2", "2026-07-27", EarningsTiming.AFTER_MARKET),
                e("2026/Q3", "2026-10-27", EarningsTiming.AFTER_MARKET)), "2026-06-29", new ArrayList<>());

        assertThat(sub(m, "lastAnnounced")).containsEntry("period", "2026/Q2");
        assertThat(sub(m, "nextScheduled")).containsEntry("period", "2026/Q3").containsEntry("tradingDaysAhead", 0);
        assertThat((String) m.get("note")).contains("盘后公布").contains("入场前就会公布");
    }

    /** 时段未知按保守处理：可能在盘前已公布，也可能在盘后，一律算尚未反映并写明。 */
    @Test
    void 判定日当天时段未知算尚未反映() {
        Map<String, Object> m = hint(List.of(e("2026/Q3", "2026-10-27", EarningsTiming.UNKNOWN)), "2026-06-29", new ArrayList<>());

        assertThat(m.get("lastAnnounced")).isNull();
        assertThat(sub(m, "nextScheduled")).containsEntry("timing", "UNKNOWN").containsEntry("tradingDaysAhead", 0);
        assertThat((String) m.get("note")).contains("时段未知").contains("按尚未反映处理");
    }

    @Test
    void 判定日当天盘中公布算已反映() {
        Map<String, Object> m = hint(List.of(e("2026/Q3", "2026-10-27", EarningsTiming.DURING_MARKET)), "2026-06-29", new ArrayList<>());

        assertThat(sub(m, "lastAnnounced")).containsEntry("period", "2026/Q3");
        assertThat(m.get("nextScheduled")).isNull();
    }

    @Test
    void 下一次预定日期按交易日数_APH与WMB() {
        Map<String, Object> aph = hint(List.of(e("2026/Q2", "2026-07-29", EarningsTiming.UNKNOWN),
                e("2026/Q3", "2026-10-28", EarningsTiming.UNKNOWN)), "2026-06-29", new ArrayList<>());
        Map<String, Object> wmb = hint(List.of(e("2026/Q2", "2026-08-03", EarningsTiming.AFTER_MARKET),
                e("2026/Q3", "2026-11-02", EarningsTiming.AFTER_MARKET)), "2026-06-29", new ArrayList<>());

        assertThat(sub(aph, "nextScheduled")).containsEntry("tradingDaysAhead", 1);
        assertThat(sub(aph, "lastAnnounced")).containsEntry("period", "2026/Q2").containsEntry("tradingDaysAgo", 64);
        assertThat(sub(wmb, "nextScheduled")).containsEntry("date", "2026-11-02").containsEntry("tradingDaysAhead", 4);
        assertThat((String) wmb.get("note")).contains("预定 2026-11-02盘后公布").contains("之后第 4 个交易日");
    }

    /** 刚公布那一季的报表没进库：标出来，并写进 caveats。同一期是否已进库按日期判（期末后 75 天内），不比期别文本。 */
    @Test
    void 刚公布的一季报表未入库时提示() {
        List<String> stale = new ArrayList<>();
        Map<String, Object> m = hint(List.of(e("2026/Q3", "2026-10-27", EarningsTiming.PRE_MARKET)), "2026-06-29", stale);

        assertThat(m.get("latestAnnouncedInFinancials")).isEqualTo(false);
        assertThat(stale).singleElement().asString().contains("2026/Q3").contains("尚未入库").contains("2026-06-29");

        List<String> fresh = new ArrayList<>();
        Map<String, Object> f = hint(List.of(e("2026/Q3", "2026-10-27", EarningsTiming.PRE_MARKET)), "2026-09-29", fresh);
        assertThat(f.get("latestAnnouncedInFinancials")).isEqualTo(true);
        assertThat(fresh).isEmpty();
    }

    @Test
    void 没有公告日时按期末推算并注明() {
        Map<String, Object> m = hint(List.of(), "2026-09-29", new ArrayList<>());

        assertThat(m.get("source")).isEqualTo("PERIOD_END_RULE");
        assertThat(m.get("lastAnnounced")).isNull();
        assertThat(m.get("nextScheduled")).isNull();
        assertThat(m.get("latestAnnouncedInFinancials")).isNull();
        assertThat((String) m.get("note")).contains("没有该标的的财报公告日记录").contains("88~108 天").contains("推算");
    }

    /** 原先下限截在 0：推算窗口整个过去了还说「0~0 天」，等于凭空断言财报就在眼前。 */
    @Test
    void 推算窗口已过去时不再说零天() {
        Map<String, Object> m = hint(List.of(), "2026-05-31", new ArrayList<>());

        assertThat((String) m.get("note")).contains("本应已经公布").contains("无法判断").doesNotContain("0~");
    }

    @Test
    void 手工分析过去的日子时注明预定日期来自现在的列表() {
        List<String> caveats = new ArrayList<>();
        EarningsCalendarHint.build(List.of(e("2026/Q3", "2026-10-28", EarningsTiming.UNKNOWN)), AS_OF, d("2026-06-29"), CALENDAR, true, caveats);

        assertThat(caveats).anyMatch(c -> c.contains("判定日当时未必已经排期"));
    }

    /** 键序决定输入 JSON 的字段顺序：要固定（前缀缓存、输入哈希）。 */
    @Test
    void 键序固定() {
        Map<String, Object> m = hint(List.of(e("2026/Q2", "2026-07-28", EarningsTiming.PRE_MARKET),
                e("2026/Q3", "2026-10-28", EarningsTiming.PRE_MARKET)), "2026-06-29", new ArrayList<>());

        assertThat(m.keySet()).containsExactly("source", "lastAnnounced", "nextScheduled", "latestQuarterEnd", "daysSinceLatestQuarterEnd",
                "latestAnnouncedInFinancials", "note");
        assertThat(sub(m, "nextScheduled").keySet()).containsExactly("period", "date", "timing", "tradingDaysAhead");
    }

    @Test
    void 日历覆盖不到的部分按工作日数() {
        // 11-06 之后不在日历里：11-09 ~ 11-13 是 5 个工作日
        assertThat(EarningsCalendarHint.tradingDaysBetween(d("2026-11-06"), d("2026-11-13"), CALENDAR)).isEqualTo(5);
        assertThat(EarningsCalendarHint.tradingDaysBetween(d("2026-10-27"), d("2026-11-13"), CALENDAR)).isEqualTo(13);
        assertThat(EarningsCalendarHint.tradingDaysBetween(d("2026-10-27"), d("2026-10-27"), CALENDAR)).isZero();
        assertThat(EarningsCalendarHint.tradingDaysBetween(d("2026-10-27"), d("2026-10-29"), new TreeSet<>())).isEqualTo(2);
    }

    /**
     * 手工分析过去的日子：报表要在判定日已经公布才发给模型（与量化选股同一口径）。
     * 2026-08-10 时 2026/Q2（库内期末 06-29，07-28 公布）已公布；判定日 07-20 时还没有，而 3.4.8 前的「让 45 天」也会放它进来。
     */
    @Test
    void 过去日子的报表按公告日判断是否已公布() {
        Instrument hlt = Instrument.us("HLT");
        Map<FinancialStatement, List<FinancialReport>> reports = Map.of(FinancialStatement.INCOME, List.of(
                report(hlt, "2026-06-29", "2026/Q2"), report(hlt, "2026-03-30", "2026/Q1"), report(hlt, "2025-12-30", "2025/FY")));
        List<EarningsEvent> events = List.of(e("2026/Q1", "2026-04-29", EarningsTiming.PRE_MARKET),
                e("2026/Q2", "2026-07-28", EarningsTiming.PRE_MARKET));

        Set<LocalDate> on0720 = SignalPayloadBuilder.unpublishedOn(d("2026-07-20"), reports, events, weekdays(d("2025-01-01"), d("2026-12-31")));
        Set<LocalDate> on0810 = SignalPayloadBuilder.unpublishedOn(d("2026-08-10"), reports, events, weekdays(d("2025-01-01"), d("2026-12-31")));

        assertThat(on0720).containsExactly(d("2026-06-29"));
        assertThat(on0810).isEmpty();
    }

    /** 判定日当天时段未知（或盘后）的公告，报表与 calendarHint 同一口径算尚未公布（开发实例实测 APH 2026-07-29 曾不一致）。 */
    @Test
    void 判定日当天时段未知的那一期报表不给() {
        Instrument aph = Instrument.us("APH");
        Map<FinancialStatement, List<FinancialReport>> reports = Map.of(FinancialStatement.INCOME, List.of(
                report(aph, "2026-06-29", "2026/Q2"), report(aph, "2026-03-30", "2026/Q1")));
        TreeSet<LocalDate> cal = weekdays(d("2025-01-01"), d("2026-12-31"));

        assertThat(SignalPayloadBuilder.unpublishedOn(d("2026-07-29"), reports, List.of(e("2026/Q1", "2026-04-29", EarningsTiming.UNKNOWN),
                e("2026/Q2", "2026-07-29", EarningsTiming.UNKNOWN)), cal)).containsExactly(d("2026-06-29"));
        assertThat(SignalPayloadBuilder.unpublishedOn(d("2026-07-29"), reports, List.of(e("2026/Q1", "2026-04-29", EarningsTiming.PRE_MARKET),
                e("2026/Q2", "2026-07-29", EarningsTiming.PRE_MARKET)), cal)).isEmpty();
        assertThat(SignalPayloadBuilder.unpublishedOn(d("2026-07-30"), reports, List.of(e("2026/Q1", "2026-04-29", EarningsTiming.UNKNOWN),
                e("2026/Q2", "2026-07-29", EarningsTiming.UNKNOWN)), cal)).isEmpty();
    }

    /** 没有公告日（ETF、选股范围之外）按法定期限兜底：年报期末 + 60 天。 */
    @Test
    void 没有公告日时报表按法定期限兜底() {
        Instrument x = Instrument.us("X");
        Map<FinancialStatement, List<FinancialReport>> reports = Map.of(FinancialStatement.INCOME, List.of(report(x, "2025-12-30", "2025/FY")));

        assertThat(SignalPayloadBuilder.unpublishedOn(d("2026-02-20"), reports, List.of(), new TreeSet<>())).containsExactly(d("2025-12-30"));
        assertThat(SignalPayloadBuilder.unpublishedOn(d("2026-03-02"), reports, List.of(), new TreeSet<>())).isEmpty();
    }

    private static FinancialReport report(Instrument i, String periodEnd, String periodText) {
        return new FinancialReport(i, FinancialStatement.INCOME, d(periodEnd), 2026, periodText, "USD", null, null, List.of());
    }
}
