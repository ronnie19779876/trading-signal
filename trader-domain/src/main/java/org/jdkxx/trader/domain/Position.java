package org.jdkxx.trader.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 券商账户里的一条持仓，券商原样口径（还没有与本系统的标的建立映射）。
 *
 * <p>brokerRef 是券商内部合约标识（盈透 conId），比代码稳定，映射到本系统标的时优先用它；
 * symbol 保留券商写法（盈透的类别股用空格，如 {@code BRK B}，本系统写 {@code BRK.B}）。
 * 数量可能是小数（碎股），也可能是 0（当天已清仓的条目券商仍可能列出）；averageCost 券商没给时为 null。
 *
 * <p>accountId 是券商原始账户号，只在服务端内存里流转，{@link #toString()} 不带它。
 */
public record Position(
        Broker broker,
        String accountId,
        String brokerRef,
        String symbol,
        String localSymbol,
        String securityType,
        String exchange,
        String primaryExchange,
        String currency,
        BigDecimal quantity,
        BigDecimal averageCost) {

    public Position {
        Objects.requireNonNull(broker, "broker");
        Objects.requireNonNull(quantity, "quantity");
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("accountId 不能为空");
        }
        if (brokerRef == null || brokerRef.isBlank()) {
            throw new IllegalArgumentException("brokerRef 不能为空");
        }
    }

    /** 防止账户号被无意打进日志。 */
    @Override
    public String toString() {
        return "Position[" + broker + " **** " + symbol + " ref=" + brokerRef + " " + securityType + " qty=" + quantity + "]";
    }
}
