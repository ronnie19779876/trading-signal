package org.jdkxx.trader.domain.selection;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Announcement date of one earnings period (Futu {@code getFinancialsEarningsPriceHistory}).
 *
 * <p>Measured: only the latest 20 quarters are returned, and they <b>include future scheduled
 * dates</b> (e.g. AAPL 2026/Q4 scheduled for 11-02), so an announcement date after the evaluation
 * day does not count as announced. The period text has the same format as the financial reports'
 * {@code period_text} ({@code 2026/Q3}), but the labels do not always agree across the two Futu
 * APIs (fiscal years ending in Jan/Feb, fiscal-year changes), so periods are matched by date
 * instead: the first announcement within 75 days after the period end. Futu gives the effective
 * trading day, so a report published on a Saturday is dated the following Monday; periods in the
 * middle of the list can be missing.
 *
 * @param pubTimeText the broker's raw time-of-day text (time zone unverified; kept for the record
 *     only, not used in calculations)
 * @param current the period the broker marks as "current"
 */
public record EarningsEvent(
    String periodText,
    int fiscalYear,
    LocalDate pubDate,
    String pubTimeText,
    EarningsTiming timing,
    boolean current) {

  public EarningsEvent {
    Objects.requireNonNull(periodText, "periodText");
    Objects.requireNonNull(pubDate, "pubDate");
    Objects.requireNonNull(timing, "timing");
  }
}
