package org.jdkxx.trader.domain.selection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * IBKR TRADES daily bar: <b>already split-adjusted</b> (as of the fetch day), not adjusted for
 * dividends; the opposite of Futu's unadjusted daily bars (measured with a read-only probe on
 * 2026-10-10 for NVDA and AAPL). Volume is on a different basis from Futu's, so quant selection
 * only uses prices.
 */
public record SplitAdjustedBar(
    LocalDate tradeDate,
    BigDecimal open,
    BigDecimal high,
    BigDecimal low,
    BigDecimal close,
    long volume) {

  public SplitAdjustedBar {
    Objects.requireNonNull(tradeDate, "tradeDate");
    Objects.requireNonNull(close, "close");
  }
}
