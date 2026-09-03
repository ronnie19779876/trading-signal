package org.jdkxx.trader.domain;

/** 跟踪的指数。classification 是该指数成分股表使用的行业分类体系。 */
public enum IndexCode {
    SP500("标普 500", "GICS"),
    NDX100("纳斯达克 100", "ICB");

    private final String displayName;
    private final String classification;

    IndexCode(String displayName, String classification) {
        this.displayName = displayName;
        this.classification = classification;
    }

    public String displayName() {
        return displayName;
    }

    public String classification() {
        return classification;
    }
}
