package org.jdkxx.trader.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Latest price of a held instrument (lastPrice from IBKR market data reqMktData, the same source as
 * the "last price" in the IBKR app).
 *
 * <p>Measured (2026-09-19): the account has real-time market data permissions (marketDataType=1);
 * the last price is <b>not equal</b> to "market value ÷ quantity" from per-position P&amp;L (GOOG
 * 346.08 vs 344.41; IBKR values positions with a price of its own), so the current price must come
 * from market data and cannot be derived from market value. {@code priorClose} is the previous
 * close from market data (tick CLOSE), equal to the app's PRIOR CLOSE (GOOG 343.68, checked against
 * a screenshot on 09-19). Either may not have arrived yet (null). {@code delayed=true} means the
 * broker downgraded to delayed market data.
 */
public record PositionPrice(
    Broker broker,
    String brokerRef,
    Instant receivedAt,
    BigDecimal last,
    BigDecimal priorClose,
    boolean delayed) {

  public PositionPrice {
    Objects.requireNonNull(broker, "broker");
    Objects.requireNonNull(receivedAt, "receivedAt");
    if (brokerRef == null || brokerRef.isBlank()) {
      throw new IllegalArgumentException("brokerRef 不能为空");
    }
  }
}
