package org.jdkxx.trader.storage.account;

import java.math.BigDecimal;

/**
 * 快照里的一条持仓。symbol 是券商原样写法，instrumentId 为空表示库里没有这个标的；
 * priceSource：BAR 当日 K 线收盘、SNAPSHOT 富途快照价、NONE 缺价。
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
        boolean cashEquivalent) {
}
