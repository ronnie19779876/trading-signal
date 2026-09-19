package org.jdkxx.trader.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * 单个持仓的盈亏与市值推送（盈透 reqPnLSingle）。{@code brokerRef} 与 {@link Position#brokerRef()} 同义（盈透 conId）。
 *
 * <p>实测（2026-09-19）：已实现盈亏恒为"未设"（Double.MAX_VALUE，映射成 null）；marketValue 即该仓市值，
 * 同一时刻各仓之和与账户汇总的股票市值精确相等。
 */
public record PositionPnl(Broker broker, String brokerRef, Instant receivedAt, BigDecimal quantity, BigDecimal daily,
                          BigDecimal unrealized, BigDecimal realized, BigDecimal marketValue) {

    public PositionPnl {
        Objects.requireNonNull(broker, "broker");
        Objects.requireNonNull(receivedAt, "receivedAt");
        if (brokerRef == null || brokerRef.isBlank()) {
            throw new IllegalArgumentException("brokerRef 不能为空");
        }
    }
}
