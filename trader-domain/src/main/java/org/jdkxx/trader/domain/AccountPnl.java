package org.jdkxx.trader.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * One push of account-level P&amp;L (IBKR reqPnL). Items the broker did not supply are null.
 *
 * <p>Measured (2026-09-19, after hours): pushed on change roughly every 5 seconds; after hours the
 * daily P&amp;L is recomputed with after-hours prices. A push is not guaranteed to be complete
 * (right after connecting the first one may cover only one position), and whether it is has nothing
 * to do with its ordinal, so the adapter does not drop the first push: since 3.0.3 it accepts a
 * value only after cross-checking it against the per-position sum ({@code
 * IbkrLiveAccount.verifyPnl}).
 */
public record AccountPnl(
    Broker broker,
    Instant receivedAt,
    BigDecimal daily,
    BigDecimal unrealized,
    BigDecimal realized) {

  public AccountPnl {
    Objects.requireNonNull(broker, "broker");
    Objects.requireNonNull(receivedAt, "receivedAt");
  }
}
