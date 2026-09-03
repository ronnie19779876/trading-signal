package org.jdkxx.trader.common.time;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class MarketClockTest {

    @Test
    void 美东日期按纽约时区计算() {
        // UTC 2026-09-04 02:00 = 美东 2026-09-03 22:00（夏令时 UTC-4）
        Instant instant = Instant.parse("2026-09-04T02:00:00Z");
        MarketClock clock = new MarketClock(Clock.fixed(instant, ZoneOffset.UTC));

        assertThat(clock.newYorkDate()).isEqualTo(LocalDate.of(2026, 9, 3));
        assertThat(clock.nowNewYork().getHour()).isEqualTo(22);
    }
}
