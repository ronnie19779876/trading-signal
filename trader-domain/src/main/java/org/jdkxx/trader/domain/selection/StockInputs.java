package org.jdkxx.trader.domain.selection;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Scoring input of one instrument on one day. A non-null {@code exclusion} means it does not take
 * part (metrics are still carried along for auditing).
 *
 * @param applicable metrics applicable to this instrument (the bank and insurance templates have no
 *     gross margin or FCF margin); the factor rule "at least half of the metrics have values"
 *     counts against it
 */
public record StockInputs(
    long instrumentId,
    String symbol,
    String sector,
    Exclusion exclusion,
    String exclusionDetail,
    Map<Metric, Double> metrics,
    Set<Metric> applicable) {

  public StockInputs {
    Objects.requireNonNull(metrics, "metrics");
    Objects.requireNonNull(applicable, "applicable");
  }
}
