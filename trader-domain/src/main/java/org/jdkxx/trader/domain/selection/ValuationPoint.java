package org.jdkxx.trader.domain.selection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/** 估值历史上的一天。亏损股的 PE、资不抵债的 PB 是负数，原样保留。 */
public record ValuationPoint(LocalDate tradeDate, BigDecimal value) {

    public ValuationPoint {
        Objects.requireNonNull(tradeDate, "tradeDate");
        Objects.requireNonNull(value, "value");
    }
}
