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
 * Availability date of quarterly reports (§25.5, revision approved by the user on 2026-10-12):
 *
 * <ol>
 *   <li>Match the announcement date <b>by nearest date</b>: the first announcement trading day
 *       within {@value #MATCH_WINDOW_DAYS} days after the period end. Not by period label: the
 *       labels of Futu's financials API and its announcement-date API do not agree (retailers with
 *       a January/February fiscal year end are one fiscal year apart, and after FERG changed its
 *       fiscal year the announcement came before the period end). 75 days is less than the earliest
 *       announcement of the next quarter (about 104 days), so a quarter missing its announcement
 *       date is never matched to the next quarter's;
 *   <li>If nothing matches (before 2021, or a period missing in the middle of Futu's list), fall
 *       back to <b>max(statutory deadline, the largest lag observed for this instrument)</b>:
 *       statutory deadline = period end + 40 days (+ 60 days for the fiscal fourth quarter), rolled
 *       forward to the next trading day when it falls on a non-trading day. In 6.1, 58 periods were
 *       measured whose real announcement came after the statutory deadline, so the rule alone would
 *       be too early (look-ahead). The maximum lag is taken over all matched periods, which can
 *       only push dates later, never earlier.
 * </ol>
 */
public final class QuarterAvailability {

  public static final int MATCH_WINDOW_DAYS = 75;
  public static final int STATUTORY_DAYS = 40;
  public static final int STATUTORY_DAYS_Q4 = 60;

  private QuarterAvailability() {}

  /**
   * @param quarters quarterly reports of one instrument (period ends already corrected to the real
   *     period end)
   * @param pubDates all announcement trading days of that instrument (regardless of period)
   * @param tradingDays trading calendar; when empty or not covering a date, only weekends are
   *     skipped
   */
  public static List<QuarterFacts> resolve(
      List<QuarterFacts> quarters,
      Collection<LocalDate> pubDates,
      NavigableSet<LocalDate> tradingDays) {
    List<Period> periods =
        quarters.stream().map(q -> new Period(q.periodEnd(), q.fiscalQ4())).toList();
    List<LocalDate> available = availableDates(periods, pubDates, tradingDays);
    List<QuarterFacts> out = new ArrayList<>(quarters.size());
    for (int i = 0; i < quarters.size(); i++) {
      out.add(quarters.get(i).withAvailableOn(available.get(i)));
    }
    return out;
  }

  /**
   * One reporting period: real period end and whether it is the fiscal fourth quarter (the annual
   * report follows the Q4 deadline).
   */
  public record Period(LocalDate periodEnd, boolean fiscalQ4) {}

  /**
   * Same rules as {@link #resolve}, dates only: the result corresponds one-to-one with {@code
   * periods} (the entry signal's model input also uses it to decide whether a report was public on
   * the evaluation day).
   */
  public static List<LocalDate> availableDates(
      List<Period> periods, Collection<LocalDate> pubDates, NavigableSet<LocalDate> tradingDays) {
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
        LocalDate statutory =
            q.periodEnd().plusDays(q.fiscalQ4() ? STATUTORY_DAYS_Q4 : STATUTORY_DAYS);
        LocalDate observed = q.periodEnd().plusDays(maxLag);
        available =
            rollToTradingDay(statutory.isAfter(observed) ? statutory : observed, tradingDays);
      }
      out.add(available);
    }
    return out;
  }

  /**
   * The day itself if it is a trading day, otherwise the next one; days beyond the calendar's
   * coverage only skip weekends.
   */
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
