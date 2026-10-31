package org.jdkxx.trader.gateway.ibkr;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Log throttling for an error code that keeps recurring: the first occurrence is logged as usual,
 * later ones are summarized per window. While reconnecting, the same code once a minute would flood
 * the log (measured: a single 25-hour gateway outage produced more than 1500 lines).
 */
final class RepeatSuppressor {

  /**
   * One decision: whether to log, how many occurrences were suppressed, and how long since the
   * first one.
   */
  record Decision(boolean log, long suppressed, Duration since) {}

  private final Duration window;
  private final Clock clock;

  private int lastKey = Integer.MIN_VALUE;
  private Instant firstAt;
  private Instant lastLoggedAt;
  private long suppressed;

  RepeatSuppressor(Duration window, Clock clock) {
    this.window = window;
    this.clock = clock;
  }

  /**
   * A different key restarts counting immediately; the same key is suppressed within the window,
   * and when the window elapses one line is let through carrying the number of suppressed
   * occurrences.
   */
  synchronized Decision offer(int key) {
    Instant now = clock.instant();
    if (key != lastKey) {
      lastKey = key;
      firstAt = now;
      lastLoggedAt = now;
      suppressed = 0;
      return new Decision(true, 0, Duration.ZERO);
    }
    if (Duration.between(lastLoggedAt, now).compareTo(window) >= 0) {
      long n = suppressed;
      suppressed = 0;
      lastLoggedAt = now;
      return new Decision(true, n, Duration.between(firstAt, now));
    }
    suppressed++;
    return new Decision(false, suppressed, Duration.between(firstAt, now));
  }

  /** Cleared once connected, so the next outage is logged from scratch. */
  synchronized void reset() {
    lastKey = Integer.MIN_VALUE;
    suppressed = 0;
  }
}
