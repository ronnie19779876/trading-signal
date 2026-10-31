package org.jdkxx.trader.core.signal.ai;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import org.jdkxx.trader.domain.holding.EarningsReaction;
import org.jdkxx.trader.domain.selection.EarningsEvent;
import org.jdkxx.trader.domain.selection.EarningsTiming;
import org.jdkxx.trader.domain.selection.QuarterAvailability;

/**
 * The {@code calendarHint} in the model input (3.4.8): based on the broker's earnings announcement
 * dates, it states whether the latest report is already reflected in the judgement-date close and
 * how many trading days after the judgement date the next one is. Pure function with a fixed key
 * order (the input hash must be stable).
 *
 * <p>Background (2026-10-27, production): previously it was only estimated as "latest stored
 * quarter end + 91 days + 25~45 days" with the lower bound clipped at 0. During earnings season a
 * new quarter's statements take a few days to reach the database, so names that had <b>just
 * reported</b> were estimated as "reporting within 0~16 days"; 5 of 7 vetoes (PFG 10-26 after
 * market, CNP / HLT / ITW / JCI 10-27 pre-market) rested on treating an event that had already
 * passed as one still to come.
 *
 * <p>Attribution is by session: entry is at the first open after the judgement date, so a
 * <b>pre-market or intraday</b> announcement on the judgement date counts as reflected;
 * <b>after-market and unknown-session</b> announcements count as not yet reflected (unknown is
 * treated conservatively, and the note says so).
 */
public final class EarningsCalendarHint {

  public static final String BROKER_CALENDAR = "BROKER_CALENDAR";
  public static final String PERIOD_END_RULE = "PERIOD_END_RULE";

  /** US quarterly cadence: the next report comes roughly 91 days after the previous one */
  static final int QUARTER_DAYS = 91;

  private EarningsCalendarHint() {}

  /**
   * @param events all announcement dates of the instrument, earliest first (may be empty: ETFs,
   *     names outside the selection universe)
   * @param latestQuarterEnd value of {@code financials.quarters[0].periodEnd} in the input (stored
   *     period end; the true period end is one day later); null when there are no quarterly reports
   * @param tradingDays trading calendar; days it does not cover are counted as weekdays
   * @param pastDate judgement date is more than 7 days before today (manual analysis of a past day)
   * @param caveats data-gap notes are appended here
   */
  public static Map<String, Object> build(
      List<EarningsEvent> events,
      LocalDate asOf,
      LocalDate latestQuarterEnd,
      NavigableSet<LocalDate> tradingDays,
      boolean pastDate,
      List<String> caveats) {
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
    m.put(
        "lastAnnounced",
        last == null
            ? null
            : event(last, "tradingDaysAgo", tradingDaysBetween(last.pubDate(), asOf, tradingDays)));
    m.put(
        "nextScheduled",
        next == null
            ? null
            : event(
                next, "tradingDaysAhead", tradingDaysBetween(asOf, next.pubDate(), tradingDays)));
    Long since = latestQuarterEnd == null ? null : ChronoUnit.DAYS.between(latestQuarterEnd, asOf);
    m.put("latestQuarterEnd", latestQuarterEnd == null ? null : latestQuarterEnd.toString());
    m.put("daysSinceLatestQuarterEnd", since);
    Boolean inFinancials = null;
    if (last != null && latestQuarterEnd != null) {
      // "Same period or not" is decided by date (announcement within 75 days of the true period
      // end), not by period text: the period labels of Futu's two endpoints do not match (§25.5)
      inFinancials =
          !last.pubDate()
              .isAfter(latestQuarterEnd.plusDays(1 + QuarterAvailability.MATCH_WINDOW_DAYS));
      if (!inFinancials) {
        caveats.add(
            "最近一次财报（"
                + last.periodText()
                + "，"
                + last.pubDate()
                + " 公布）的报表尚未入库：financials 的最新一季期末为 "
                + latestQuarterEnd
                + "，不含该期");
      }
    }
    m.put("latestAnnouncedInFinancials", inFinancials);
    m.put("note", broker ? brokerNote(last, next, asOf, tradingDays) : ruleNote(since));
    if (broker && pastDate) {
      caveats.add("判定日早于今天：公告日取自现在的券商列表，nextScheduled 是后来实际（或现在预定）的日期，判定日当时未必已经排期");
    }
    return m;
  }

  /**
   * Whether the judgement-date close already reflects this announcement: an earlier day, or
   * pre-market / intraday on the judgement date.
   */
  static boolean reflected(EarningsEvent e, LocalDate asOf) {
    return EarningsReaction.reflected(e, asOf);
  }

  private static Map<String, Object> event(EarningsEvent e, String daysKey, int days) {
    Map<String, Object> x = new LinkedHashMap<>();
    x.put("period", e.periodText());
    x.put("date", e.pubDate().toString());
    x.put("timing", e.timing().name());
    x.put(daysKey, days);
    return x;
  }

  /**
   * Number of trading days in (from, to]; the part the calendar does not cover is counted in
   * weekdays (future holidays are over-counted; only used for hints).
   */
  public static int tradingDaysBetween(
      LocalDate from, LocalDate to, NavigableSet<LocalDate> tradingDays) {
    if (!to.isAfter(from)) {
      return 0;
    }
    LocalDate covered = tradingDays.isEmpty() ? from : tradingDays.last();
    int n = 0;
    if (covered.isAfter(from)) {
      LocalDate end = to.isBefore(covered) ? to : covered;
      n += tradingDays.subSet(from, false, end, true).size();
    }
    for (LocalDate d = (covered.isAfter(from) ? covered : from).plusDays(1);
        !d.isAfter(to);
        d = d.plusDays(1)) {
      if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
        n++;
      }
    }
    return n;
  }

  private static String brokerNote(
      EarningsEvent last, EarningsEvent next, LocalDate asOf, NavigableSet<LocalDate> tradingDays) {
    StringBuilder s = new StringBuilder("财报日期取自券商公告日列表；入场在判定日之后的第一个开盘。");
    if (last != null) {
      int ago = tradingDaysBetween(last.pubDate(), asOf, tradingDays);
      s.append("最近一次财报（")
          .append(last.periodText())
          .append("）已于 ")
          .append(last.pubDate())
          .append(timingText(last.timing()))
          .append(ago == 0 ? "公布，就在判定日当天" : "公布，在判定日之前 " + ago + " 个交易日")
          .append("，判定日收盘价已经反映，这次事件已经过去。");
    }
    if (next == null) {
      s.append("券商尚未给出下一次财报的预定日期");
      if (last != null) {
        s.append("；按季度节奏，大约在上次公布后 ")
            .append(QUARTER_DAYS)
            .append(" 天前后（约 ")
            .append(last.pubDate().plusDays(QUARTER_DAYS))
            .append("），这是估计");
      }
      s.append("。");
    } else if (next.pubDate().equals(asOf)) {
      s.append("下一次财报（").append(next.periodText()).append("）在判定日当天");
      s.append(
          next.timing() == EarningsTiming.UNKNOWN
              ? "公布、时段未知：可能已在盘前公布，也可能在盘后，按尚未反映处理，最晚在入场前公布。"
              : "盘后公布：判定日收盘价尚未反映，入场前就会公布。");
    } else {
      s.append("下一次财报（")
          .append(next.periodText())
          .append("）预定 ")
          .append(next.pubDate())
          .append(timingText(next.timing()))
          .append("公布，在判定日之后第 ")
          .append(tradingDaysBetween(asOf, next.pubDate(), tradingDays))
          .append(" 个交易日。");
    }
    return s.toString();
  }

  private static String ruleNote(Long since) {
    if (since == null) {
      return "没有该标的的财报公告日记录，也没有季报，无法判断财报时间。";
    }
    long lo = QUARTER_DAYS + 25 - since;
    long hi = QUARTER_DAYS + 45 - since;
    String head =
        "没有该标的的财报公告日记录，只能按期末推算：美股季报通常在期末后约 25~45 天公布、下一期期末约在最近期末后 "
            + QUARTER_DAYS
            + " 天；距最近期末 "
            + since
            + " 天，";
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
