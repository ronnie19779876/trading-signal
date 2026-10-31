package org.jdkxx.trader.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Funds summary of a broker account, exactly as the broker reports it. Missing fields are null,
 * because the broker returns different tags for different account types.
 *
 * <p>Identity: cash + stock market value + accrued dividend + <b>accrued interest</b> = net
 * liquidation (measured on IBKR to the cent); the phase-3 reconciliation relies on it. Accrued
 * interest (IBKR {@code $LEDGER-AccruedCash}) was added in 3.1.3: while the cash balance was small
 * it was always 0 and the three-term sum happened to hold; after cash grew past twenty thousand on
 * 2026-09-28 it became 1.49, which was exactly the gap in the identity (it was then misread as a
 * reconciliation WARN and a PARTIAL job, although the data was correct). {@code raw} keeps the
 * broker's original tags and values <b>with the account number removed</b> (the value of IBKR's
 * {@code $LEDGER-AccountOrGroup} is the plain-text account number).
 *
 * <p>{@code accountId} is the broker's raw account number; it only travels in server-side memory
 * and {@link #toString()} leaves it out.
 */
public record AccountSummary(
    Broker broker,
    String accountId,
    Instant receivedAt,
    String currency,
    BigDecimal netLiquidation,
    BigDecimal totalCash,
    BigDecimal stockMarketValue,
    BigDecimal grossPositionValue,
    BigDecimal availableFunds,
    BigDecimal buyingPower,
    BigDecimal excessLiquidity,
    BigDecimal unrealizedPnl,
    BigDecimal realizedPnl,
    BigDecimal accruedDividend,
    BigDecimal accruedInterest,
    Map<String, String> raw) {

  public AccountSummary {
    Objects.requireNonNull(broker, "broker");
    Objects.requireNonNull(receivedAt, "receivedAt");
    if (accountId == null || accountId.isBlank()) {
      throw new IllegalArgumentException("accountId 不能为空");
    }
    raw = raw == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(raw));
  }

  /** Keeps the account number from being logged by accident. */
  @Override
  public String toString() {
    return "AccountSummary["
        + broker
        + " **** "
        + currency
        + " @"
        + receivedAt
        + " tags="
        + raw.size()
        + "]";
  }
}
