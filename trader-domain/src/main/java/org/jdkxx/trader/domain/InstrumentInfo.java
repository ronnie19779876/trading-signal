package org.jdkxx.trader.domain;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.Objects;

/**
 * 券商侧的标的参考数据（合约明细）。brokerRef 是券商内部标识（盈透 conId）。
 * tradingHours / liquidHours 保留券商原始文本，交易时段的解析放到第 2 期。
 * securityType 是券商的证券类型（盈透 stockType：COMMON / ADR / REIT / NY REG SHRS …），量化选股据此排除 ADR 与 REITs；没有时为 null。
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
        String liquidHours,
        String securityType) {

    public InstrumentInfo {
        Objects.requireNonNull(instrument, "instrument");
        Objects.requireNonNull(broker, "broker");
    }
}
