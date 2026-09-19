package org.jdkxx.trader.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * 持仓标的的最新价（盈透行情 reqMktData 的 lastPrice，与盈透 App 的"最新价"同源）。
 *
 * <p>实测（2026-09-19）：账户有实时行情权限（marketDataType=1）；最新价<b>不等于</b>逐只盈亏里"市值 ÷ 数量"
 * （GOOG 346.08 对 344.41——盈透的持仓估值价另有口径），所以现价只能取行情，不能从市值倒推。
 * priorClose 是行情里的前收（tick CLOSE），与 App 的 PRIOR CLOSE 同值（GOOG 343.68，09-19 对照截图）。
 * 两者各自可能还没到（为 null）。delayed=true 表示券商降级成了延迟行情。
 */
public record PositionPrice(Broker broker, String brokerRef, Instant receivedAt, BigDecimal last, BigDecimal priorClose,
                            boolean delayed) {

    public PositionPrice {
        Objects.requireNonNull(broker, "broker");
        Objects.requireNonNull(receivedAt, "receivedAt");
        if (brokerRef == null || brokerRef.isBlank()) {
            throw new IllegalArgumentException("brokerRef 不能为空");
        }
    }
}
