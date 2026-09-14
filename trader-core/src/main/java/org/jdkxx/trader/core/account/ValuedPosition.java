package org.jdkxx.trader.core.account;

import org.jdkxx.trader.domain.Position;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 映射到本系统标的并估值后的一条持仓。instrumentId 为空表示库里没有这个标的；price 为空表示缺价。
 */
public record ValuedPosition(Position position, Long instrumentId, BigDecimal price, PriceSource priceSource,
                             boolean cashEquivalent) {

    public enum PriceSource {
        /** 当日 K 线收盘。 */
        BAR,
        /** 富途快照价（库里没有当日 K 线时兜底；收盘后到次日盘前冻结在当日收盘）。 */
        SNAPSHOT,
        /** 缺价。 */
        NONE
    }

    public boolean stock() {
        return "STK".equals(position.securityType());
    }

    public String symbol() {
        return position.symbol();
    }

    public BigDecimal marketValue() {
        return price == null ? null : position.quantity().multiply(price).setScale(4, RoundingMode.HALF_UP);
    }

    public BigDecimal costBasis() {
        return position.averageCost() == null ? null
                : position.quantity().multiply(position.averageCost()).setScale(4, RoundingMode.HALF_UP);
    }

    public BigDecimal unrealizedPnl() {
        BigDecimal mv = marketValue();
        BigDecimal cb = costBasis();
        return mv == null || cb == null ? null : mv.subtract(cb);
    }
}
