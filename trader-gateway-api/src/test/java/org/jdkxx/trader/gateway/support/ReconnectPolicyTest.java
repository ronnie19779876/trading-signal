package org.jdkxx.trader.gateway.support;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class ReconnectPolicyTest {

    @Test
    void 指数退避并封顶() {
        ReconnectPolicy p = new ReconnectPolicy(Duration.ofSeconds(5), Duration.ofSeconds(60), -1, 0);
        Random r = new Random(1);
        assertThat(p.delayFor(1, r)).isEqualTo(Duration.ofSeconds(5));
        assertThat(p.delayFor(2, r)).isEqualTo(Duration.ofSeconds(10));
        assertThat(p.delayFor(4, r)).isEqualTo(Duration.ofSeconds(40));
        assertThat(p.delayFor(5, r)).isEqualTo(Duration.ofSeconds(60));
        assertThat(p.delayFor(20, r)).isEqualTo(Duration.ofSeconds(60));
        assertThat(p.exhausted(100)).isFalse();
    }

    @Test
    void 抖动在范围内且次数上限生效() {
        ReconnectPolicy p = new ReconnectPolicy(Duration.ofSeconds(10), Duration.ofSeconds(10), 3, 0.2);
        Random r = new Random(7);
        for (int i = 0; i < 50; i++) {
            long ms = p.delayFor(1, r).toMillis();
            assertThat(ms).isBetween(8_000L, 12_000L);
        }
        assertThat(p.exhausted(3)).isFalse();
        assertThat(p.exhausted(4)).isTrue();
    }
}
