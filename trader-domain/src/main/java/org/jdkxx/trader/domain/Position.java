package org.jdkxx.trader.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One position in a broker account, exactly as the broker reports it (not yet mapped to an
 * instrument of this system).
 *
 * <p>{@code brokerRef} is the broker's internal contract id (IBKR conId). It is more stable than
 * the symbol and is preferred when mapping to an instrument of this system; {@code symbol} keeps
 * the broker's spelling (IBKR writes share classes with a space, e.g. {@code BRK B}, while this
 * system writes {@code BRK.B}). The quantity may be fractional (fractional shares) or 0 (the broker
 * may still list a position closed today); {@code averageCost} is null when the broker did not
 * supply it.
 *
 * <p>{@code accountId} is the broker's raw account number; it only travels in server-side memory
 * and {@link #toString()} leaves it out.
 */
public record Position(
    Broker broker,
    String accountId,
    String brokerRef,
    String symbol,
    String localSymbol,
    String securityType,
    String exchange,
    String primaryExchange,
    String currency,
    BigDecimal quantity,
    BigDecimal averageCost) {

  public Position {
    Objects.requireNonNull(broker, "broker");
    Objects.requireNonNull(quantity, "quantity");
    if (accountId == null || accountId.isBlank()) {
      throw new IllegalArgumentException("accountId 不能为空");
    }
    if (brokerRef == null || brokerRef.isBlank()) {
      throw new IllegalArgumentException("brokerRef 不能为空");
    }
  }

  /** Keeps the account number from being logged by accident. */
  @Override
  public String toString() {
    return "Position["
        + broker
        + " **** "
        + symbol
        + " ref="
        + brokerRef
        + " "
        + securityType
        + " qty="
        + quantity
        + "]";
  }
}
