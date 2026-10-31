package org.jdkxx.trader.domain;

import java.util.Locale;
import java.util.Objects;

/**
 * Minimal identity of an instrument: market + symbol. Symbols are normalized to upper case without
 * any broker prefix ("AAPL", "00700"); each broker's own contract / security object is mapped from
 * it by the adapter layer.
 */
public record Instrument(Market market, String symbol) {

  public Instrument {
    Objects.requireNonNull(market, "market");
    if (symbol == null || symbol.isBlank()) {
      throw new IllegalArgumentException("symbol 不能为空");
    }
    symbol = symbol.trim().toUpperCase(Locale.ROOT);
  }

  public static Instrument us(String symbol) {
    return new Instrument(Market.US, symbol);
  }

  @Override
  public String toString() {
    return market + ":" + symbol;
  }
}
