package org.jdkxx.trader.domain;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.Objects;

/**
 * 券商侧的标的参考数据（合约明细）。brokerRef 是券商内部标识（盈透 conId）。
 * tradingHours / liquidHours 保留券商原始文本，交易时段的解析放到第 2 期。
 */
public record InstrumentInfo(
        Instrument instrument,
        Broker broker,
        String brokerRef,
        String name,
        String primaryExchange,
        String currency,
        BigDecimal minTick,
        ZoneId timeZone,
        String tradingHours,
        String liquidHours) {

    public InstrumentInfo {
        Objects.requireNonNull(instrument, "instrument");
        Objects.requireNonNull(broker, "broker");
    }
}
