package org.jdkxx.trader.common.ratelimit;

import java.time.Duration;

/** Replaceable sleep, so tests can drive the limiters with virtual time. */
@FunctionalInterface
public interface Sleeper {

  void sleep(Duration duration) throws InterruptedException;

  Sleeper REAL =
      duration -> Thread.sleep(duration.toMillis(), (int) (duration.toNanos() % 1_000_000));
}
