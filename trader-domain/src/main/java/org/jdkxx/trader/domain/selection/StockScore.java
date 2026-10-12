package org.jdkxx.trader.domain.selection;

import java.util.Map;

/**
 * 一只标的某天的打分结果。没评级（被排除、或四个因子不齐）时 composite / rating 为 null。
 *
 * @param metricPct    各指标在板块内的百分位（0~100，越大越好）
 * @param compositePct 合成分在当天全部可评级标的里的百分位（0~100）
 */
public record StockScore(long instrumentId, String symbol, String sector, Exclusion exclusion, String exclusionDetail,
                         Map<Metric, Double> metrics, Map<Metric, Double> metricPct, Map<Factor, Double> factorScores,
                         Double composite, Double compositePct, Rating rating) {

    public boolean rated() {
        return rating != null;
    }
}
