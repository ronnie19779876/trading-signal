package org.jdkxx.trader.domain.selection;

/** Metrics of model F1 (§25.4); all of them are "higher is better". */
public enum Metric {
  /** 1 / PE(TTM); negative for loss-makers, which naturally ranks them last */
  EARNINGS_YIELD(Factor.VALUE),
  /** 1 / PB */
  BOOK_YIELD(Factor.VALUE),
  /** 1 / PS */
  SALES_YIELD(Factor.VALUE),
  /** Revenue TTM, year over year */
  REVENUE_GROWTH(Factor.GROWTH),
  /** Diluted EPS TTM, year over year */
  EPS_GROWTH(Factor.GROWTH),
  /** Gross margin TTM (only the standard template has it) */
  GROSS_MARGIN(Factor.PROFITABILITY),
  /**
   * Net income attributable to shareholders TTM ÷ average of opening and closing shareholders'
   * equity
   */
  ROE(Factor.PROFITABILITY),
  /** Free cash flow TTM ÷ revenue TTM (not computed for the bank and insurance templates) */
  FCF_MARGIN(Factor.PROFITABILITY),
  /** Return over 63 trading days */
  MOMENTUM_3M(Factor.MOMENTUM),
  /** Return over 126 trading days */
  MOMENTUM_6M(Factor.MOMENTUM),
  /** Return over 252 trading days */
  MOMENTUM_12M(Factor.MOMENTUM),
  /**
   * Return from 252 to 21 trading days back ("12 minus 1"); only used by historical-test variants
   * (§30.7), never by F1
   */
  MOMENTUM_12_1(Factor.MOMENTUM);

  private final Factor factor;

  Metric(Factor factor) {
    this.factor = factor;
  }

  public Factor factor() {
    return factor;
  }
}
