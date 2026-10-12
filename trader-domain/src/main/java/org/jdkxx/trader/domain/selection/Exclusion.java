package org.jdkxx.trader.domain.selection;

/** 不参与当天打分的原因（§25.3），落库供审计。 */
public enum Exclusion {
    /** 盈透 stockType 为 ADR / NY REG SHRS / REIT，或查不到类型（不猜） */
    SECURITY_TYPE,
    /** 没有 GICS 板块，覆盖表里也没有 */
    NO_SECTOR,
    /** 板块组内合格标的不足 */
    SECTOR_TOO_SMALL,
    /** 当天不复权收盘价低于下限 */
    PRICE_BELOW_MIN,
    /** 截至当天没有可用的收盘价（未上市、停牌太久） */
    NO_PRICE,
    /** 截至当天已公告的季报不足 8 季 */
    INSUFFICIENT_HISTORY
}
