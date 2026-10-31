package org.jdkxx.trader.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Push of a single position's P&amp;L and market value (IBKR reqPnLSingle). {@code brokerRef} means
 * the same as {@link Position#brokerRef()} (IBKR conId).
 *
 * <p>Measured (2026-09-19): realized P&amp;L is always "unset" (Double.MAX_VALUE, mapped to null);
 * {@code marketValue} is the position's market value, and at any instant the sum over positions
 * equals the account summary's stock market value exactly.
 */
public record PositionPnl(
    Broker broker,
    String brokerRef,
    Instant receivedAt,
    BigDecimal quantity,
    BigDecimal daily,
    BigDecimal unrealized,
    BigDecimal realized,
    BigDecimal marketValue) {

  public PositionPnl {
    Objects.requireNonNull(broker, "broker");
    Objects.requireNonNull(receivedAt, "receivedAt");
    if (brokerRef == null || brokerRef.isBlank()) {
      throw new IllegalArgumentException("brokerRef 不能为空");
    }
  }
}
