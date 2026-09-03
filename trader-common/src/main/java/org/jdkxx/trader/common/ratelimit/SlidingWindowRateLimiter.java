package org.jdkxx.trader.common.ratelimit;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.LongSupplier;

/**
 * 滑动窗口限流：窗口内最多 maxCalls 次，且相邻两次间隔不小于 minInterval。
 * 对应券商文档里"30 秒内最多 N 次、连续两次不少于 X 毫秒"的写法。
 *
 * <p>线程安全：所有状态在 synchronized 块内更新；等待发生在锁外，等完再重新竞争。
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

    public SlidingWindowRateLimiter(String name, RateLimitSpec spec, Duration minInterval, Duration maxWait) {
        this(name, spec, minInterval, maxWait, System::nanoTime, Sleeper.REAL);
    }

    public SlidingWindowRateLimiter(String name, RateLimitSpec spec, Duration minInterval, Duration maxWait,
                                    LongSupplier nanoTime, Sleeper sleeper) {
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
        return reserveOrWait() <= 0;
    }

    /** 拿到许可返回 0；否则返回还需等待的纳秒数（不占用许可）。 */
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
