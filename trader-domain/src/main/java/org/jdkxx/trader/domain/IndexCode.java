package org.jdkxx.trader.domain;

/**
 * 跟踪的指数。classification 是该指数成分股表使用的行业分类体系。
 * nominalSize 是指数自身定义的公司数（不是我们库里的行数）：实际代码数会略多，
 * 因为同一家公司的多类别股各占一行（例如标普 500 的 GOOGL / GOOG、BRK.B）。巡检用它判断集合有没有缩水。
 */
public enum IndexCode {
    SP500("标普 500", "GICS", 500),
    NDX100("纳斯达克 100", "ICB", 100);

    private final String displayName;
    private final String classification;
    private final int nominalSize;

    IndexCode(String displayName, String classification, int nominalSize) {
        this.displayName = displayName;
        this.classification = classification;
        this.nominalSize = nominalSize;
    }

    /** 指数定义的公司数；实际代码数允许略多（多类别股）。 */
    public int nominalSize() {
        return nominalSize;
    }

    public String displayName() {
        return displayName;
    }

    public String classification() {
        return classification;
    }
}
