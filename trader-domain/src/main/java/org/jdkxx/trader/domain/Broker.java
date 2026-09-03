package org.jdkxx.trader.domain;

/**
 * 接入的券商及其在本系统中的职责分工（2026-09-03 拍板）。
 */
public enum Broker {

    /** 持仓账户：每日账户资金、收盘持仓价格与盈亏、交易下单。 */
    IBKR("盈透", "持仓账户：资金、持仓盈亏、下单"),

    /** 跟踪与分析：标普 500 + 纳指 100 日 K 线、基本面、标的池与持仓的实时行情订阅（不落库）、入场信号与 AI 分析。 */
    FUTU("富途", "跟踪与分析：行情、基本面、实时订阅、信号");

    private final String displayName;
    private final String role;

    Broker(String displayName, String role) {
        this.displayName = displayName;
        this.role = role;
    }

    public String displayName() {
        return displayName;
    }

    public String role() {
        return role;
    }
}
