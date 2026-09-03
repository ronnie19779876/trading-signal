package org.jdkxx.trader.common.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SlidingWindowRateLimiterTest {

    private final AtomicLong clock = new AtomicLong();
    private final List<Duration> sleeps = new ArrayList<>();
    private final Sleeper sleeper = d -> {
        sleeps.add(d);
        clock.addAndGet(d.toNanos());
    };

    private SlidingWindowRateLimiter limiter(String spec, Duration minInterval, Duration maxWait) {
        return new SlidingWindowRateLimiter("t", RateLimitSpec.parse(spec), minInterval, maxWait, clock::get, sleeper);
    }

    @Test
    void 窗口内超过次数就等到最早一次滑出窗口() {
        SlidingWindowRateLimiter l = limiter("3/30s", null, Duration.ofMinutes(1));
        l.acquire();
        clock.addAndGet(Duration.ofSeconds(10).toNanos());
        l.acquire();
        l.acquire();
        assertThat(sleeps).isEmpty();

        l.acquire();   // 第 4 次：要等第 1 次（t=0）滑出 30 秒窗口，当前 t=10s → 等 20s
        assertThat(sleeps).containsExactly(Duration.ofSeconds(20));
    }

    @Test
    void 最小间隔独立生效() {
        SlidingWindowRateLimiter l = limiter("100/30s", Duration.ofMillis(20), Duration.ofSeconds(1));
        l.acquire();
        l.acquire();
        assertThat(sleeps).containsExactly(Duration.ofMillis(20));
    }

    @Test
    void 等待超过上限抛异常且不占用许可() {
        SlidingWindowRateLimiter l = limiter("1/30s", null, Duration.ofSeconds(5));
        l.acquire();
        assertThatThrownBy(l::acquire).isInstanceOf(RateLimitExceededException.class);
        assertThat(l.tryAcquire()).isFalse();
        clock.addAndGet(Duration.ofSeconds(30).toNanos());
        assertThat(l.tryAcquire()).isTrue();
    }
}
