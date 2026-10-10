package org.jdkxx.trader.domain.selection;

/** 财报公告时段。打分在收盘后、执行在次日，所以时点口径里不区分（§25.5），只留档。 */
public enum EarningsTiming {
    PRE_MARKET,
    DURING_MARKET,
    AFTER_MARKET,
    UNKNOWN
}
