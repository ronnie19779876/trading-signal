package org.jdkxx.trader.domain;

/**
 * Trading market. Phase 0 only needs to distinguish US and Hong Kong stocks; the two brokers encode
 * markets differently, so the mapping lives in each adapter.
 */
public enum Market {
  US("美股"),
  HK("港股");

  private final String displayName;

  Market(String displayName) {
    this.displayName = displayName;
  }

  public String displayName() {
    return displayName;
  }
}
