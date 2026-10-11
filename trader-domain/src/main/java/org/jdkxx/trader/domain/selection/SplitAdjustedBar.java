package org.jdkxx.trader.domain.selection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * 盈透 TRADES 日线：<b>已按拆股调整</b>（截至抓取当天），不按分红调整——与富途不复权日 K 口径相反（2026-10-10 只读探针实测 NVDA、AAPL）。
 * 成交量口径与富途不同，量化选股只用价格。
 */
public record SplitAdjustedBar(LocalDate tradeDate, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close,
                               long volume) {

    public SplitAdjustedBar {
        Objects.requireNonNull(tradeDate, "tradeDate");
        Objects.requireNonNull(close, "close");
    }
}
