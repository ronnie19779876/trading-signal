package org.jdkxx.trader.domain.selection;

/**
 * Five rating tiers with a forced distribution of 10 / 20 / 40 / 20 / 10 percent (§25.4, §25.10
 * item 2).
 */
public enum Rating {
  STRONG_BUY,
  BUY,
  HOLD,
  SELL,
  STRONG_SELL
}
