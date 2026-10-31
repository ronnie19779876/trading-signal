package org.jdkxx.trader.domain.signal;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * One daily bar used for evaluation: already converted to the evaluation basis (split-adjusted,
 * price and volume on the same basis), computed in double. Evaluation is a deterministic pure
 * function, and the same inputs give bit-identical results on any JVM (floating point is always
 * strict since Java 17).
 */
public record SignalBar(
    LocalDate date, double open, double high, double low, double close, double volume) {

  public SignalBar {
    Objects.requireNonNull(date, "date");
    if (!(open > 0 && high > 0 && low > 0 && close > 0)
        || high < low
        || volume < 0
        || Double.isNaN(volume)
        || Double.isInfinite(high)) {
      throw new IllegalArgumentException("K 线数值非法：" + date);
    }
  }

  /**
   * Requires strictly ascending dates without duplicates; unordered input would silently break
   * every "previous N bars" computation.
   */
  static void requireAscending(List<SignalBar> bars) {
    for (int i = 1; i < bars.size(); i++) {
      if (!bars.get(i).date().isAfter(bars.get(i - 1).date())) {
        throw new IllegalArgumentException("K 线必须按日期严格升序：" + bars.get(i).date());
      }
    }
  }
}
