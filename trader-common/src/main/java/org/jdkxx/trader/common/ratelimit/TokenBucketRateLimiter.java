package org.jdkxx.trader.common.ratelimit;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * 令牌桶：稳定速率 ratePerSecond，允许 burst 个突发。适合"每秒不超过 N 条消息"这类总量限制。
 */
public final class TokenBucketRateLimiter implements RateLimiter {

    private final String name;
    private final double ratePerNano;
    private final double burst;
    private final long maxWaitNanos;
    private final LongSupplier nanoTime;
    private final Sleeper sleeper;
    private double tokens;
    private long last;

    public TokenBucketRateLimiter(String name, double ratePerSecond, int burst, Duration maxWait) {
        this(name, ratePerSecond, burst, maxWait, System::nanoTime, Sleeper.REAL);
    }

    public TokenBucketRateLimiter(String name, double ratePerSecond, int burst, Duration maxWait,
                                  LongSupplier nanoTime, Sleeper sleeper) {
        if (ratePerSecond <= 0 || burst <= 0) {
            throw new IllegalArgumentException("ratePerSecond 与 burst 必须为正");
        }
        this.name = name;
        this.ratePerNano = ratePerSecond / 1_000_000_000d;
        this.burst = burst;
        this.maxWaitNanos = maxWait == null ? Long.MAX_VALUE : maxWait.toNanos();
        this.nanoTime = nanoTime;
        this.sleeper = sleeper;
        this.tokens = burst;
        this.last = nanoTime.getAsLong();
    }

    @Override
    public void acquire() {
        long waited = 0;
        while (true) {
            long wait = takeOrWait();
            if (wait <= 0) {
                return;
            }
            waited += wait;
            if (waited > maxWaitNanos) {
                throw new RateLimitExceededException("限频「" + name + "」等待超过上限 " + Duration.ofNanos(maxWaitNanos));
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
        return takeOrWait() <= 0;
    }

    private synchronized long takeOrWait() {
        long now = nanoTime.getAsLong();
        tokens = Math.min(burst, tokens + (now - last) * ratePerNano);
        last = now;
        if (tokens >= 1) {
            tokens -= 1;
            return 0;
        }
        return (long) Math.ceil((1 - tokens) / ratePerNano);
    }
}
