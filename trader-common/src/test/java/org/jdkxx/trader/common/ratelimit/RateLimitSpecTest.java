package org.jdkxx.trader.common.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimitSpecTest {

    @Test
    void 解析次数与窗口() {
        assertThat(RateLimitSpec.parse("60/30s")).isEqualTo(new RateLimitSpec(60, Duration.ofSeconds(30)));
        assertThat(RateLimitSpec.parse(" 15 / 10m ")).isEqualTo(new RateLimitSpec(15, Duration.ofMinutes(10)));
        assertThat(RateLimitSpec.parse("1/500ms")).isEqualTo(new RateLimitSpec(1, Duration.ofMillis(500)));
    }

    @Test
    void 非法写法报错() {
        assertThatThrownBy(() -> RateLimitSpec.parse("60 per 30s")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RateLimitSpec.parse("0/30s")).isInstanceOf(IllegalArgumentException.class);
    }
}
