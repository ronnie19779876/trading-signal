package org.jdkxx.trader.gateway.support;

import java.time.Duration;
import java.util.Random;

/**
 * 指数退避：initialDelay × 2^(attempt-1)，封顶 maxDelay，±jitter 抖动；maxAttempts < 0 表示不限次数。
 */
public record ReconnectPolicy(Duration initialDelay, Duration maxDelay, int maxAttempts, double jitter) {

    public ReconnectPolicy {
        if (initialDelay == null || initialDelay.isNegative() || initialDelay.isZero()) {
            throw new IllegalArgumentException("initialDelay 必须为正");
        }
        if (maxDelay == null || maxDelay.compareTo(initialDelay) < 0) {
            throw new IllegalArgumentException("maxDelay 不能小于 initialDelay");
        }
        if (jitter < 0 || jitter >= 1) {
            throw new IllegalArgumentException("jitter 取值 [0,1)");
        }
    }

    public static ReconnectPolicy defaults() {
        return new ReconnectPolicy(Duration.ofSeconds(5), Duration.ofSeconds(60), -1, 0.2);
    }

    /** @param attempt 从 1 开始的重连序号 */
    public Duration delayFor(int attempt, Random random) {
        double base = initialDelay.toMillis() * Math.pow(2, Math.max(0, attempt - 1));
        base = Math.min(base, maxDelay.toMillis());
        double factor = 1 + (random.nextDouble() * 2 - 1) * jitter;
        return Duration.ofMillis(Math.max(1, (long) (base * factor)));
    }

    public boolean exhausted(int attempt) {
        return maxAttempts >= 0 && attempt > maxAttempts;
    }
}
