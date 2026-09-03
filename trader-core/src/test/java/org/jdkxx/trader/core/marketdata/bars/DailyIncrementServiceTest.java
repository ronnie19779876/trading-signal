package org.jdkxx.trader.core.marketdata.bars;

import org.jdkxx.trader.core.marketdata.MarketDataProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DailyIncrementServiceTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");
    private static final List<LocalDate> DAYS = List.of(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 3), LocalDate.of(2026, 9, 4));

    private static MarketDataProperties props() {
        return new MarketDataProperties(null, null, new MarketDataProperties.Refresh(90, 65, true, 1000, 5), null, null, false, "", "America/New_York");
    }

    @Test
    void 收盘前不把今天当成应有的K线() {
        ZonedDateTime beforeClose = ZonedDateTime.of(2026, 9, 3, 15, 0, 0, 0, NY);
        ZonedDateTime afterClose = ZonedDateTime.of(2026, 9, 3, 17, 30, 0, 0, NY);
        assertThat(DailyIncrementService.expectedLatestTradingDay(DAYS, beforeClose)).isEqualTo(LocalDate.of(2026, 9, 2));
        assertThat(DailyIncrementService.expectedLatestTradingDay(DAYS, afterClose)).isEqualTo(LocalDate.of(2026, 9, 3));
        assertThat(DailyIncrementService.expectedLatestTradingDay(List.of(), afterClose)).isNull();
    }

    @Test
    void 缺几个交易日就补几根加重叠() {
        DailyIncrementService s = new DailyIncrementService(props(), null, null, null, null, null, null, Clock.systemUTC());
        assertThat(s.countFor(null, LocalDate.of(2026, 9, 3), DAYS)).isEqualTo(1000);
        assertThat(s.countFor(LocalDate.of(2026, 9, 3), LocalDate.of(2026, 9, 3), DAYS)).isZero();
        assertThat(s.countFor(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 3), DAYS)).isEqualTo(2 + 5);
    }
}
