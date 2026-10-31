package org.jdkxx.trader.core.selection;

/** The four parts of quant selection input backfill (§25.6). */
public enum BackfillPart {
  /** IBKR split-adjusted daily bars plus the seam check */
  PRICES,
  /** Futu valuation history PE / PB / PS */
  VALUATION,
  /** Futu earnings announcement dates */
  EARNINGS,
  /** Futu quarterly reports (income statement, balance sheet, cash flow statement) */
  FINANCIALS,
  /**
   * IBKR security type (stockType: COMMON / ADR / REIT / NY REG SHRS), used to exclude ADRs and
   * REITs
   */
  TYPES
}
