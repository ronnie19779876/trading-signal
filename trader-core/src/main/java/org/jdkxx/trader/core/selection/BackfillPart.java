package org.jdkxx.trader.core.selection;

/** 量化选股输入回补的四个部分（§25.6）。 */
public enum BackfillPart {
    /** 盈透拆股调整日线 + 接缝核对 */
    PRICES,
    /** 富途估值历史 PE / PB / PS */
    VALUATION,
    /** 富途财报公告日 */
    EARNINGS,
    /** 富途季报（利润表、资产负债表、现金流量表） */
    FINANCIALS
}
