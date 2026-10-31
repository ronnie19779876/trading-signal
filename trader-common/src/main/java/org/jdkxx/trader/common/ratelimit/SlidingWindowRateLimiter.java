package org.jdkxx.trader.common.ratelimit;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.LongSupplier;

/**
 * Sliding-window rate limiter: at most {@code maxCalls} calls within the window, and consecutive
 * calls at least {@code minInterval} apart. This mirrors the broker documentation's phrasing of "at
 * most N calls per 30 seconds, with no less than X milliseconds between two consecutive calls".
 *
 * <p>Thread safety: all state is updated inside a synchronized method; the actual waiting happens
 * outside the lock, after which the caller competes for a permit again. A thread that wakes up is
 * therefore not guaranteed the next permit (no fairness), but no thread ever sleeps while holding
 * the lock.
 *
 * <p>The accumulated wait used for the {@code maxWait} check is the sum of the computed waits, not
 * wall-clock time.
 */
public final class SlidingWindowRateLimiter implements RateLimiter {

  private final String name;
  private final int maxCalls;
  private final long windowNanos;
  private final long minIntervalNanos;
  private final long maxWaitNanos;
  private final LongSupplier nanoTime;
  private final Sleeper sleeper;
  private final Deque<Long> stamps = new ArrayDeque<>();

  public SlidingWindowRateLimiter(
      String name, RateLimitSpec spec, Duration minInterval, Duration maxWait) {
    this(name, spec, minInterval, maxWait, System::nanoTime, Sleeper.REAL);
  }

  public SlidingWindowRateLimiter(
      String name,
      RateLimitSpec spec,
      Duration minInterval,
      Duration maxWait,
      LongSupplier nanoTime,
      Sleeper sleeper) {
    this.name = name;
    this.maxCalls = spec.maxCalls();
    this.windowNanos = spec.window().toNanos();
    this.minIntervalNanos = minInterval == null ? 0 : minInterval.toNanos();
    this.maxWaitNanos = maxWait == null ? Long.MAX_VALUE : maxWait.toNanos();
    this.nanoTime = nanoTime;
    this.sleeper = sleeper;
  }

  @Override
  public void acquire() {
    long waited = 0;
    while (true) {
      long wait = reserveOrWait();
      if (wait <= 0) {
        return;
      }
      waited += wait;
      if (waited > maxWaitNanos) {
        throw new RateLimitExceededException(
            "限频「" + name + "」等待超过上限 " + Duration.ofNanos(maxWaitNanos));
      }
      try {
        sleeper.sleep(Duration.ofNanos(wait));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RateLimitExceededException("限频「" + name + "」等待被中断");
      }
    }
  }

  @Override
  public boolean tryAcquire() {
    return reserveOrWait() <= 0;
  }

  /**
   * Returns 0 when a permit was taken (its timestamp is recorded); otherwise returns how many
   * nanoseconds the caller still has to wait, without reserving anything. Timestamps older than the
   * window are evicted first, so the deque never holds more than {@code maxCalls} entries.
   */
  private synchronized long reserveOrWait() {
    long now = nanoTime.getAsLong();
    while (!stamps.isEmpty() && now - stamps.peekFirst() >= windowNanos) {
      stamps.pollFirst();
    }
    long wait = 0;
    if (stamps.size() >= maxCalls) {
      wait = Math.max(wait, windowNanos - (now - stamps.peekFirst()));
    }
    if (!stamps.isEmpty() && minIntervalNanos > 0) {
      wait = Math.max(wait, minIntervalNanos - (now - stamps.peekLast()));
    }
    if (wait > 0) {
      return wait;
    }
    stamps.addLast(now);
    return 0;
  }

  public String name() {
    return name;
  }
}
