package org.jdkxx.trader.core.marketdata;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotWindowTest {

    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final LocalDate LABOR_DAY = LocalDate.of(2026, 9, 7);
    private static final Predicate<LocalDate> TRADING = d -> d.getDayOfWeek() != DayOfWeek.SATURDAY
            && d.getDayOfWeek() != DayOfWeek.SUNDAY && !d.equals(LABOR_DAY);

    private static Optional<LocalDate> at(String localEt) {
        return SnapshotWindow.asOfDate(ZonedDateTime.of(LocalDateTime.parse(localEt), ET), TRADING);
    }

    @Test
    void 交易日收盘落定后属于当天() {
        assertThat(at("2026-09-14T18:00")).contains(LocalDate.of(2026, 9, 14));
        assertThat(at("2026-09-14T16:15")).contains(LocalDate.of(2026, 9, 14));
    }

    @Test
    void 收盘落定前与盘前开始后都不在窗口() {
        assertThat(at("2026-09-14T16:14")).isEmpty();
        assertThat(at("2026-09-14T11:00")).isEmpty();
        assertThat(at("2026-09-15T04:00")).isEmpty();
    }

    @Test
    void 午夜后到盘前仍属于前一个交易日() {
        assertThat(at("2026-09-15T01:00")).contains(LocalDate.of(2026, 9, 14));
        assertThat(at("2026-09-12T01:00")).as("周六凌晨属于周五").contains(LocalDate.of(2026, 9, 11));
    }

    @Test
    void 休市日不在窗口() {
        assertThat(at("2026-09-13T20:00")).as("周日晚").isEmpty();
        assertThat(at("2026-09-07T18:00")).as("劳工节").isEmpty();
        assertThat(at("2026-09-08T02:00")).as("劳工节后的凌晨：前一天休市").isEmpty();
    }
}
