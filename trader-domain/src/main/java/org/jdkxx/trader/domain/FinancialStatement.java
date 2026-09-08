package org.jdkxx.trader.domain;

/** 财务报表类型。与富途 FinancialStatementsType 一一对应，映射在网关适配层。 */
public enum FinancialStatement {

    /** 利润表 */
    INCOME,
    /** 资产负债表 */
    BALANCE_SHEET,
    /** 现金流量表 */
    CASH_FLOW,
    /** 主要指标 */
    MAIN_INDEX
}
