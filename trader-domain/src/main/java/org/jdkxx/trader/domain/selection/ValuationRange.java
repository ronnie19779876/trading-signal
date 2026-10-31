package org.jdkxx.trader.domain.selection;

/**
 * Lookback range of valuation history. YEAR10 measured as daily values from 2016-10 on, about 2513
 * days; MONTH3 refreshes the recent period after an announcement.
 */
public enum ValuationRange {
  MONTH3,
  YEAR10
}
