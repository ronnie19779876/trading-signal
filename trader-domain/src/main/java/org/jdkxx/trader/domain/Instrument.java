package org.jdkxx.trader.domain;

import java.util.Locale;
import java.util.Objects;

/**
 * 标的的最小标识：市场 + 代码。代码统一大写、不带任何券商前缀（"AAPL"、"00700"），
 * 券商各自的合约/证券对象由接入层从它映射得到。
 */
public record Instrument(Market market, String symbol) {

    public Instrument {
        Objects.requireNonNull(market, "market");
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol 不能为空");
        }
        symbol = symbol.trim().toUpperCase(Locale.ROOT);
    }

    public static Instrument us(String symbol) {
        return new Instrument(Market.US, symbol);
    }

    @Override
    public String toString() {
        return market + ":" + symbol;
    }
}
