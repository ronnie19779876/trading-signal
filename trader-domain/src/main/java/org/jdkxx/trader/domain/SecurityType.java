package org.jdkxx.trader.domain;

/**
 * Broker security type as mapped by the adapters. Futu files US REITs as Trust, which the mapping
 * turns into ETF, so ETF here does not reliably mean "has no financial reports".
 */
public enum SecurityType {
  STOCK,
  ETF,
  OTHER
}
