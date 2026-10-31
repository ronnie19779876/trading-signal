package org.jdkxx.trader.storage.account;

import com.fasterxml.jackson.annotation.JsonRawValue;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One account snapshot row. accountKey is a keyed HMAC and accountMask is the masked form for
 * display; the plain account number is never stored. recon is the raw JSON of the reconciliation
 * details and is embedded as-is in API responses.
 */
public record AccountSnapshotRow(
    long id,
    String broker,
    String accountKey,
    String accountMask,
    LocalDate asOfDate,
    Instant takenAt,
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
    /**
     * Accrued interest (IBKR $LEDGER-AccruedCash); collected since 3.1.3, null in older snapshots.
     */
    BigDecimal accruedInterest,
    BigDecimal positionValue,
    int positions,
    String reconStatus,
    @JsonRawValue String recon,
    Long jobRunId) {}
