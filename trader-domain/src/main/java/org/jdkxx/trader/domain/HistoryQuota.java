package org.jdkxx.trader.domain;

import java.time.Instant;
import java.util.List;

/**
 * Futu history-bar quota: used / remaining within the rolling 7-day window, plus which instruments
 * used it.
 */
public record HistoryQuota(int used, int remain, List<Item> items) {

  public record Item(Instrument instrument, String name, Instant requestedAt) {}

  public int total() {
    return used + remain;
  }
}
