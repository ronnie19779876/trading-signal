package org.jdkxx.trader.domain.selection;

import java.util.Map;

/**
 * Scoring result of one instrument on one day. When not rated (excluded, or not all four factors
 * available), {@code composite} / {@code rating} are null.
 *
 * @param metricPct percentile of each metric within the sector (0~100, higher is better)
 * @param compositePct percentile of the composite score among all instruments rated that day
 *     (0~100)
 */
public record StockScore(
    long instrumentId,
    String symbol,
    String sector,
    Exclusion exclusion,
    String exclusionDetail,
    Map<Metric, Double> metrics,
    Map<Metric, Double> metricPct,
    Map<Factor, Double> factorScores,
    Double composite,
    Double compositePct,
    Rating rating) {

  public boolean rated() {
    return rating != null;
  }
}
