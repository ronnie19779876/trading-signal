package org.jdkxx.trader.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TokenBucketRateLimiterTest {

  @Test
  void 突发用尽后按速率放行() {
    AtomicLong clock = new AtomicLong();
    List<Duration> sleeps = new ArrayList<>();
    TokenBucketRateLimiter l =
        new TokenBucketRateLimiter(
            "t",
            10,
            2,
            Duration.ofSeconds(5),
            clock::get,
            d -> {
              sleeps.add(d);
              clock.addAndGet(d.toNanos());
            });
    l.acquire();
    l.acquire();
    assertThat(sleeps).isEmpty();
    l.acquire(); // 10/s, so the next token arrives 100ms later
    assertThat(sleeps).hasSize(1);
    assertThat(sleeps.get(0)).isEqualTo(Duration.ofMillis(100));
  }
}
