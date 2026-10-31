package org.jdkxx.trader.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One daily bar (unadjusted). Prices and amounts are BigDecimal; volume is in shares. {@code
 * blank=true} marks a day without trades for which the broker filled in an empty bar.
 *
 * <p>{@code lastClose} is the broker's previous close and {@code pe} is the broker's static P/E; a
 * {@code pe} of 0 means "no valid value" (loss-making periods and funds), not a real zero.
 */
public record DailyBar(
    Instrument instrument,
    LocalDate tradeDate,
    BigDecimal open,
    BigDecimal high,
    BigDecimal low,
    BigDecimal close,
    BigDecimal lastClose,
    long volume,
    BigDecimal turnover,
    BigDecimal turnoverRate,
    BigDecimal changeRate,
    BigDecimal pe,
    boolean blank) {

  public DailyBar {
    Objects.requireNonNull(instrument, "instrument");
    Objects.requireNonNull(tradeDate, "tradeDate");
  }

  /** Replaces only the prices and keeps everything else (used for price adjustment). */
  public DailyBar withPrices(
      BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, BigDecimal lastClose) {
    return new DailyBar(
        instrument,
        tradeDate,
        open,
        high,
        low,
        close,
        lastClose,
        volume,
        turnover,
        turnoverRate,
        changeRate,
        pe,
        blank);
  }
}
