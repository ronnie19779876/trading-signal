package org.jdkxx.trader.domain;

/**
 * Financial statement type. Maps one-to-one onto Futu's FinancialStatementsType; the mapping lives
 * in the gateway adapter.
 */
public enum FinancialStatement {

  /** Income statement */
  INCOME,
  /** Balance sheet */
  BALANCE_SHEET,
  /** Cash flow statement */
  CASH_FLOW,
  /** Key indicators */
  MAIN_INDEX
}
