package org.jdkxx.trader.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * 账户级盈亏的一次推送（盈透 reqPnL）。券商没给的项为 null。
 *
 * <p>实测（2026-09-19，盘后）：约每 5 秒按变化推送一次；盘后用盘后价重算当日盈亏；
 * 首条不完整（只含一只持仓），由适配器丢弃，这里拿到的都是完整值。
 */
public record AccountPnl(Broker broker, Instant receivedAt, BigDecimal daily, BigDecimal unrealized, BigDecimal realized) {

    public AccountPnl {
        Objects.requireNonNull(broker, "broker");
        Objects.requireNonNull(receivedAt, "receivedAt");
    }
}
