package org.jdkxx.trader.domain.selection;

/**
 * The three measures of Futu's valuation history. PE is TTM (identical digit for digit to the
 * valuation snapshot's {@code pe_ttm}, measured on three instruments on 2026-09-08).
 */
public enum ValuationMetric {
  PE,
  PB,
  PS
}
