package org.jdkxx.trader.domain;

/**
 * 交易市场。第 0 期只需区分美股与港股；两家券商对市场的编码不同，映射放在各自的接入层。
 */
public enum Market {
    US("美股"),
    HK("港股");

    private final String displayName;

    Market(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
