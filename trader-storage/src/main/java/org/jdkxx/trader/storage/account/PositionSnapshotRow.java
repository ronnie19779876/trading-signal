package org.jdkxx.trader.storage.account;

import java.math.BigDecimal;

/**
 * One position in a snapshot. symbol is the broker's own spelling (class shares contain a space),
 * and a null instrumentId means the instrument is not in the database. priceSource: BAR = that
 * day's daily-bar close, SNAPSHOT = Futu snapshot price, NONE = no price.
 */
public record PositionSnapshotRow(
    String brokerRef,
    String symbol,
    Long instrumentId,
    String securityType,
    String currency,
    String exchange,
    BigDecimal quantity,
    BigDecimal averageCost,
    BigDecimal price,
    String priceSource,
    BigDecimal marketValue,
    BigDecimal costBasis,
    BigDecimal unrealizedPnl,
    boolean cashEquivalent) {}
