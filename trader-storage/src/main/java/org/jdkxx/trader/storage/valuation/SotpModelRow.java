package org.jdkxx.trader.storage.valuation;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Stored form of one set of sum-of-the-parts valuation assumptions. {@code segments} is the raw
 * jsonb text and is deserialized by the layer above (storage does not know the domain's JSON
 * structure, so the storage layer does not have to change whenever the business structure does).
 */
public record SotpModelRow(
    Long id,
    long instrumentId,
    String symbol,
    String name,
    LocalDate asOf,
    int targetYear,
    BigDecimal discountRate,
    BigDecimal targetShares,
    BigDecimal targetNetCash,
    String segments,
    String note,
    Instant createdAt,
    Instant updatedAt) {}
