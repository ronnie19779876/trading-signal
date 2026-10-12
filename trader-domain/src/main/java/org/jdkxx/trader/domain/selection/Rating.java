package org.jdkxx.trader.domain.selection;

/** 五档评级，强制分布 10 / 20 / 40 / 20 / 10（§25.4、§25.10 第 2 条）。 */
public enum Rating {
    STRONG_BUY,
    BUY,
    HOLD,
    SELL,
    STRONG_SELL
}
