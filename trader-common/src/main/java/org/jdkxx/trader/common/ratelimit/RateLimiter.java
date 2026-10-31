package org.jdkxx.trader.common.ratelimit;

/**
 * Rate limiter. Callers are always our own service threads (never an SDK callback thread), which is
 * why {@link #acquire()} is allowed to block briefly. Calling it from an SDK callback or dispatch
 * thread would stall every other reply queued behind it (see the gateway thread discipline in
 * CLAUDE.md).
 */
public interface RateLimiter {

  /**
   * Takes one permit, blocking while a wait is needed; throws {@link RateLimitExceededException}
   * once the implementation's maximum wait is exceeded.
   */
  void acquire();

  /** Non-blocking: takes a permit if one is available right now, otherwise returns false. */
  boolean tryAcquire();
}
