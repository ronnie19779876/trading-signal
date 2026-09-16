package org.jdkxx.trader.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * 一次除权除息事件的复权因子（富途口径）：
 * 前复权价 = 不复权价 × fwdA + fwdB（对 exDate 之前的价格生效）；后复权价 = 不复权价 × bwdA + bwdB。
 *
 * <p>{@code companyActFlag} 是公司行动位图（1 拆股、2 合股、4 送股、8 转增、16 配股、32 增发、64 分红、128 特别股息、256 分拆），
 * 一个事件可以同时带多位（实测 65 = 拆股 + 分红、258 = 合股 + 分拆），这时 fwdA 是混合比例。
 * 需要单独拿股数变动比例时（信号判定的结构口径）用 base/ert：拆股 1 → 10 为 splitBase=1、splitErt=10。
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
        int splitErt,
        int joinBase,
        int joinErt,
        int bonusBase,
        int bonusErt,
        int transferBase,
        int transferErt) {

    public static final long ACT_SPLIT = 1;
    public static final long ACT_JOIN = 2;
    public static final long ACT_BONUS = 4;
    public static final long ACT_TRANSFER = 8;
    public static final long ACT_DIVIDEND = 64;
    public static final long ACT_SP_DIVIDEND = 128;
    public static final long ACT_SPIN_OFF = 256;

    /** 只有拆股比例的旧形态（测试与只关心复权价的调用方用）。 */
    public RehabFactor(Instrument instrument, LocalDate exDate, BigDecimal fwdA, BigDecimal fwdB, BigDecimal bwdA,
                       BigDecimal bwdB, long companyActFlag, BigDecimal dividend, BigDecimal spDividend,
                       int splitBase, int splitErt) {
        this(instrument, exDate, fwdA, fwdB, bwdA, bwdB, companyActFlag, dividend, spDividend, splitBase, splitErt,
                0, 0, 0, 0, 0, 0);
    }

    public boolean has(long act) {
        return (companyActFlag & act) != 0;
    }

    public RehabFactor {
        Objects.requireNonNull(instrument, "instrument");
        Objects.requireNonNull(exDate, "exDate");
        fwdA = fwdA == null ? BigDecimal.ONE : fwdA;
        fwdB = fwdB == null ? BigDecimal.ZERO : fwdB;
        bwdA = bwdA == null ? BigDecimal.ONE : bwdA;
        bwdB = bwdB == null ? BigDecimal.ZERO : bwdB;
    }
}
