package org.jdkxx.trader.common.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class TokenBucketRateLimiterTest {

    @Test
    void 突发用尽后按速率放行() {
        AtomicLong clock = new AtomicLong();
        List<Duration> sleeps = new ArrayList<>();
        TokenBucketRateLimiter l = new TokenBucketRateLimiter("t", 10, 2, Duration.ofSeconds(5), clock::get, d -> {
            sleeps.add(d);
            clock.addAndGet(d.toNanos());
        });
        l.acquire();
        l.acquire();
        assertThat(sleeps).isEmpty();
        l.acquire();   // 10/s → 下一枚令牌 100ms 后
        assertThat(sleeps).hasSize(1);
        assertThat(sleeps.get(0)).isEqualTo(Duration.ofMillis(100));
    }
}
