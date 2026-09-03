package org.jdkxx.trader.common.time;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * 市场时钟。系统面向美股，所有"交易日"语义都以 America/New_York 计算；
 * 存储一律 UTC（timestamptz），展示时再换算。注入 {@link Clock} 便于测试固定时间。
 */
public final class MarketClock {

    public static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private final Clock clock;

    public MarketClock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static MarketClock system() {
        return new MarketClock(Clock.system(NEW_YORK));
    }

    public Instant now() {
        return clock.instant();
    }

    public ZonedDateTime nowNewYork() {
        return now().atZone(NEW_YORK);
    }

    /** 当前美东日期。注意：这只是日历日，是否为交易日由交易日历判定（后续期数实现）。 */
    public LocalDate newYorkDate() {
        return nowNewYork().toLocalDate();
    }
}
