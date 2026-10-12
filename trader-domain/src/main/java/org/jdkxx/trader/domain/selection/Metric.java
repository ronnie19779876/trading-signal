package org.jdkxx.trader.domain.selection;

/** 模型 F1 的指标（§25.4），全部「越大越好」。 */
public enum Metric {
    /** 1 / PE(TTM)；亏损为负，自然排最后 */
    EARNINGS_YIELD(Factor.VALUE),
    /** 1 / PB */
    BOOK_YIELD(Factor.VALUE),
    /** 1 / PS */
    SALES_YIELD(Factor.VALUE),
    /** 营收 TTM 同比 */
    REVENUE_GROWTH(Factor.GROWTH),
    /** 稀释 EPS TTM 同比 */
    EPS_GROWTH(Factor.GROWTH),
    /** 毛利率 TTM（只有标准模板有） */
    GROSS_MARGIN(Factor.PROFITABILITY),
    /** 归母净利 TTM ÷ 期初期末归母权益均值 */
    ROE(Factor.PROFITABILITY),
    /** 自由现金流 TTM ÷ 营收 TTM（银行、保险模板不计） */
    FCF_MARGIN(Factor.PROFITABILITY),
    /** 63 个交易日涨幅 */
    MOMENTUM_3M(Factor.MOMENTUM),
    /** 126 个交易日涨幅 */
    MOMENTUM_6M(Factor.MOMENTUM),
    /** 252 个交易日涨幅 */
    MOMENTUM_12M(Factor.MOMENTUM);

    private final Factor factor;

    Metric(Factor factor) {
        this.factor = factor;
    }

    public Factor factor() {
        return factor;
    }
}
