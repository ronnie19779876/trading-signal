package org.jdkxx.trader.common.ratelimit;

import java.time.Duration;

/** 可替换的休眠，便于在测试里用虚拟时间。 */
@FunctionalInterface
public interface Sleeper {

    void sleep(Duration duration) throws InterruptedException;

    Sleeper REAL = duration -> Thread.sleep(duration.toMillis(), (int) (duration.toNanos() % 1_000_000));
}
