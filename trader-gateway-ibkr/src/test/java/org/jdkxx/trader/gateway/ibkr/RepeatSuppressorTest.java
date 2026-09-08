package org.jdkxx.trader.gateway.ibkr;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class RepeatSuppressorTest {

    /** 可推进的时钟，避免测试里真的等 10 分钟。 */
    private static final class Ticking extends Clock {
        private Instant now = Instant.parse("2026-09-09T00:00:00Z");

        void plusSeconds(long s) {
            now = now.plusSeconds(s);
        }

        @Override public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override public Instant instant() {
            return now;
        }
    }

    @Test
    void 同一错误码窗口内只记一条_到点带上被压掉的条数() {
        Ticking clock = new Ticking();
        RepeatSuppressor s = new RepeatSuppressor(Duration.ofMinutes(10), clock);

        assertThat(s.offer(502).log()).isTrue();

        for (int i = 0; i < 9; i++) {
            clock.plusSeconds(60);
            assertThat(s.offer(502).log()).as("窗口内第 %d 次", i + 1).isFalse();
        }

        clock.plusSeconds(60);
        RepeatSuppressor.Decision d = s.offer(502);
        assertThat(d.log()).isTrue();
        assertThat(d.suppressed()).isEqualTo(9);
        assertThat(d.since()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void 换了错误码或重连之后立刻重新首告() {
        Ticking clock = new Ticking();
        RepeatSuppressor s = new RepeatSuppressor(Duration.ofMinutes(10), clock);

        s.offer(502);
        clock.plusSeconds(60);
        assertThat(s.offer(502).log()).isFalse();

        assertThat(s.offer(1100).log()).as("换个码").isTrue();

        clock.plusSeconds(60);
        assertThat(s.offer(1100).log()).isFalse();
        s.reset();
        assertThat(s.offer(1100).log()).as("重连后").isTrue();
    }
}
