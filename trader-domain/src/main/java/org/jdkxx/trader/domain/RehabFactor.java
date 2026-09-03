package org.jdkxx.trader.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * 一次除权除息事件的复权因子（富途口径）：
 * 前复权价 = 不复权价 × fwdA + fwdB（对 exDate 之前的价格生效）；后复权价 = 不复权价 × bwdA + bwdB。
 */
public record RehabFactor(
        Instrument instrument,
        LocalDate exDate,
        BigDecimal fwdA,
        BigDecimal fwdB,
        BigDecimal bwdA,
        BigDecimal bwdB,
        long companyActFlag,
        BigDecimal dividend,
        BigDecimal spDividend,
        int splitBase,
        int splitErt) {

    public RehabFactor {
        Objects.requireNonNull(instrument, "instrument");
        Objects.requireNonNull(exDate, "exDate");
        fwdA = fwdA == null ? BigDecimal.ONE : fwdA;
        fwdB = fwdB == null ? BigDecimal.ZERO : fwdB;
        bwdA = bwdA == null ? BigDecimal.ONE : bwdA;
        bwdB = bwdB == null ? BigDecimal.ZERO : bwdB;
    }
}
