package org.jdkxx.trader.domain.selection;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Fundamental inputs of one quarter (single-quarter values; measured in 6.1: the 4 quarterly cash
 * flows sum to the annual report). Missing values are null.
 *
 * @param periodEnd real period end (stored period end + 1: Futu dates are uniformly one day early)
 * @param fiscalQ4 whether the period is the fiscal fourth quarter (statutory deadline 60 days,
 *     otherwise 40)
 * @param template field template, which decides which metrics can be computed
 * @param availableOn availability date (announcement trading day or the fallback rule, see {@link
 *     QuarterAvailability})
 */
public record QuarterFacts(
    LocalDate periodEnd,
    boolean fiscalQ4,
    Template template,
    LocalDate availableOn,
    Double revenue,
    Double grossProfit,
    Double netIncome,
    Double epsDiluted,
    Double equity,
    Double freeCashFlow) {

  /**
   * The three field templates of Futu financials (measured on the dev database 2026-10-12: standard
   * 8xxx, bank 9xxx, insurance 10xxx).
   */
  public enum Template {
    STANDARD,
    BANK,
    INSURANCE;

    public boolean financial() {
      return this != STANDARD;
    }
  }

  public QuarterFacts {
    Objects.requireNonNull(periodEnd, "periodEnd");
    Objects.requireNonNull(template, "template");
  }

  public QuarterFacts withAvailableOn(LocalDate d) {
    return new QuarterFacts(
        periodEnd,
        fiscalQ4,
        template,
        d,
        revenue,
        grossProfit,
        netIncome,
        epsDiluted,
        equity,
        freeCashFlow);
  }
}
