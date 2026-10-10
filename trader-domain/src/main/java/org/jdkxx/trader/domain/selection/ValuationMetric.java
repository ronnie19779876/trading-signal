package org.jdkxx.trader.domain.selection;

/** 富途估值历史的三种口径。PE 是 TTM（与估值快照的 {@code pe_ttm} 逐位一致，2026-09-08 实测三只）。 */
public enum ValuationMetric {
    PE,
    PB,
    PS
}
