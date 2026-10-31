package org.jdkxx.trader.domain.selection.sim;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * "Consistently profitable" criteria (ARCHITECTURE §26.8, set by the user on 2026-10-13, before
 * looking at any results). All three must hold at once:
 *
 * <ol>
 *   <li>annualized &gt;= 6%: annualized = (final equity ÷ initial capital)^(365 ÷ days) − 1, days
 *       counted from the first entry date to the end date;
 *   <li>beats SPY over the same period: cumulative account return &gt; SPY's return over the same
 *       period (open of the first entry date to the close of the end date); a tie does not count as
 *       beating;
 *   <li>12-month observation period completed: the end date is not earlier than one year after the
 *       first entry date (by calendar year, correct across leap years).
 * </ol>
 *
 * Before 12 months the first two are still computed but the verdict is fixed to "observing". Pure
 * function; it fetches no data.
 *
 * @param days days elapsed from the first entry date to the end date (0 on the entry day)
 * @param annualized annualized return; null when days is 0 (cannot annualize)
 * @param beatSpy null when SPY's return for the period is missing (cannot decide)
 * @param observationEnds the day the 12 months are complete
 */
public record SimCriteria(
    LocalDate start,
    LocalDate asOf,
    Long days,
    Double accountReturn,
    Double annualized,
    Double spyReturn,
    Boolean annualizedMet,
    Boolean beatSpy,
    boolean observationMet,
    LocalDate observationEnds,
    Verdict verdict) {

  /** Pass mark: 6% annualized (target 6~8%) */
  public static final double MIN_ANNUALIZED = 0.06;

  /**
   * Floating-point tolerance for comparisons: exactly at the pass mark passes, a tie with SPY does
   * not count as beating it
   */
  static final double EPS = 1e-9;

  public enum Verdict {
    /** No entry yet */
    NOT_STARTED,
    /** Less than 12 months; no conclusion */
    OBSERVING,
    PASS,
    FAIL,
    /** 12 months complete but equity or SPY data is missing, so no decision is possible */
    UNDETERMINED
  }

  /**
   * @param start first entry date; null if none
   * @param asOf end date (the day of the daily equity value)
   * @param initial initial capital
   * @param equity final equity (after the close, cash + positions); null if missing
   * @param spyReturn SPY's return over the same period; null if missing
   */
  public static SimCriteria evaluate(
      LocalDate start, LocalDate asOf, double initial, Double equity, Double spyReturn) {
    if (start == null || asOf == null) {
      return new SimCriteria(
          start, asOf, null, null, null, null, null, null, false, null, Verdict.NOT_STARTED);
    }
    long days = ChronoUnit.DAYS.between(start, asOf);
    LocalDate ends = start.plusYears(1);
    boolean observed = !asOf.isBefore(ends);
    Double account = equity == null || initial <= 0 ? null : equity / initial - 1;
    Double annualized =
        account == null || days <= 0 ? null : Math.pow(equity / initial, 365.0 / days) - 1;
    Boolean annualizedMet = annualized == null ? null : annualized >= MIN_ANNUALIZED - EPS;
    // A tie does not count as beating SPY; keep a floating-point tolerance here too, otherwise
    // 550000 ÷ 500000 − 1 = 0.10000000000000009 would turn a tie into a win
    Boolean beat = account == null || spyReturn == null ? null : account > spyReturn + EPS;
    Verdict v;
    if (!observed) {
      v = Verdict.OBSERVING;
    } else if (annualizedMet == null || beat == null) {
      v = Verdict.UNDETERMINED;
    } else {
      v = annualizedMet && beat ? Verdict.PASS : Verdict.FAIL;
    }
    return new SimCriteria(
        start, asOf, days, account, annualized, spyReturn, annualizedMet, beat, observed, ends, v);
  }
}
