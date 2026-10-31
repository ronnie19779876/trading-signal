package org.jdkxx.trader.domain.selection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One day of valuation history. The PE of a loss-making stock and the PB of an insolvent one are
 * negative and kept as is.
 */
public record ValuationPoint(LocalDate tradeDate, BigDecimal value) {

  public ValuationPoint {
    Objects.requireNonNull(tradeDate, "tradeDate");
    Objects.requireNonNull(value, "value");
  }
}
