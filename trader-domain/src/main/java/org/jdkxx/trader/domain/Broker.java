package org.jdkxx.trader.domain;

/**
 * The connected brokers and their division of responsibilities in this system (decided 2026-09-03).
 */
public enum Broker {

  /**
   * Holdings account: daily account funds, closing position prices and P&amp;L, order placement.
   */
  IBKR("盈透", "持仓账户：资金、持仓盈亏、下单"),

  /**
   * Tracking and analysis: S&amp;P 500 + Nasdaq-100 daily bars, fundamentals, real-time quote
   * subscriptions for the pool and holdings (not persisted), entry signals and AI analysis.
   */
  FUTU("富途", "跟踪与分析：行情、基本面、实时订阅、信号");

  private final String displayName;
  private final String role;

  Broker(String displayName, String role) {
    this.displayName = displayName;
    this.role = role;
  }

  public String displayName() {
    return displayName;
  }

  public String role() {
    return role;
  }
}
