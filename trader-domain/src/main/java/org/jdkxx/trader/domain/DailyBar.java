package org.jdkxx.trader.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * 一根日 K 线（不复权）。价格与金额用 BigDecimal，成交量为股数。blank=true 表示当天无成交、由券商补的空 K。
 */
public record DailyBar(
        Instrument instrument,
        LocalDate tradeDate,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal lastClose,
        long volume,
        BigDecimal turnover,
        BigDecimal turnoverRate,
        BigDecimal changeRate,
        BigDecimal pe,
        boolean blank) {

    public DailyBar {
        Objects.requireNonNull(instrument, "instrument");
        Objects.requireNonNull(tradeDate, "tradeDate");
    }

    /** 只换价格、其余不变（复权用）。 */
    public DailyBar withPrices(BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, BigDecimal lastClose) {
        return new DailyBar(instrument, tradeDate, open, high, low, close, lastClose, volume, turnover, turnoverRate,
                changeRate, pe, blank);
    }
}
