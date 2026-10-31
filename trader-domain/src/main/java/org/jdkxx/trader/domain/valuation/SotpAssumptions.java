package org.jdkxx.trader.domain.valuation;

import java.time.LocalDate;
import java.util.List;

/**
 * One set of sum-of-the-parts valuation assumptions.
 *
 * <p>Net cash and share count each come in a "current" and a "target year" version: the current
 * values are filled in automatically by the system (balance sheet and valuation snapshot), while
 * this record stores the <b>target-year forecasts</b>, which do not default to the current values:
 * a self-owned Robotaxi fleet and AI compute are heavy capital spending that may burn through the
 * cash, and stock compensation and buybacks change the share count.
 *
 * @param asOf valuation base date (discounting starts from this day)
 * @param targetYear target year; values are discounted from the end of the target year
 * @param discountRate required return as a <b>fraction</b> (pass 0.10 for 10%)
 * @param targetShares total shares in the target year
 * @param targetNetCash net cash in the target year (may be negative)
 * @param segments business lines, at least one
 */
public record SotpAssumptions(
    LocalDate asOf,
    int targetYear,
    double discountRate,
    double targetShares,
    double targetNetCash,
    List<SotpSegment> segments) {

  public SotpAssumptions {
    if (asOf == null) {
      throw new IllegalArgumentException("缺估值基准日");
    }
    if (targetYear < asOf.getYear()) {
      throw new IllegalArgumentException("目标年 " + targetYear + " 早于基准日所在年 " + asOf.getYear());
    }
    if (discountRate <= -1.0 || discountRate > 1.0) {
      throw new IllegalArgumentException("要求回报率要传小数（10% 传 0.10），收到 " + discountRate);
    }
    if (targetShares <= 0) {
      throw new IllegalArgumentException("目标年股数必须为正");
    }
    if (segments == null || segments.isEmpty()) {
      throw new IllegalArgumentException("至少要有一条业务线");
    }
    segments = List.copyOf(segments);
  }

  /** Discount horizon: years from the base date to the end of the target year (days ÷ 365.25). */
  public double horizonYears() {
    LocalDate end = LocalDate.of(targetYear, 12, 31);
    return java.time.temporal.ChronoUnit.DAYS.between(asOf, end) / 365.25d;
  }

  /**
   * Discount factor. <b>It cannot be switched off</b>: business value × P/E yields a value in the
   * target year, which cannot be compared with today's price without discounting it back (at 10%
   * over 4.3 years the factor is 0.664, a difference of a third).
   */
  public double discountFactor() {
    return 1.0d / Math.pow(1.0d + discountRate, horizonYears());
  }
}
