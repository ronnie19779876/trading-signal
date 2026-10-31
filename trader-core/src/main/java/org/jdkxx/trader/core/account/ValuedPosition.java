package org.jdkxx.trader.core.account;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.jdkxx.trader.domain.Position;

/**
 * One position after mapping to this system's instrument and valuing it. A null instrumentId means
 * the instrument is not in the database; a null price means no price. Amounts are rounded HALF_UP
 * to 4 decimals.
 */
public record ValuedPosition(
    Position position,
    Long instrumentId,
    BigDecimal price,
    PriceSource priceSource,
    boolean cashEquivalent) {

  public enum PriceSource {
    /** Close of the day's bar. */
    BAR,
    /**
     * Futu snapshot price (fallback when there is no bar for the day; from the close to the next
     * pre-market it stays frozen at the day's close).
     */
    SNAPSHOT,
    /** No price. */
    NONE
  }

  public boolean stock() {
    return "STK".equals(position.securityType());
  }

  public String symbol() {
    return position.symbol();
  }

  public BigDecimal marketValue() {
    return price == null
        ? null
        : position.quantity().multiply(price).setScale(4, RoundingMode.HALF_UP);
  }

  public BigDecimal costBasis() {
    return position.averageCost() == null
        ? null
        : position.quantity().multiply(position.averageCost()).setScale(4, RoundingMode.HALF_UP);
  }

  public BigDecimal unrealizedPnl() {
    BigDecimal mv = marketValue();
    BigDecimal cb = costBasis();
    return mv == null || cb == null ? null : mv.subtract(cb);
  }
}
