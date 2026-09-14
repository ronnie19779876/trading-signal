package org.jdkxx.trader.core.marketdata.bars;

import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SettledCutoffTest {

    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final LocalDate FRI = LocalDate.of(2026, 9, 11);
    private static final LocalDate MON = LocalDate.of(2026, 9, 14);
    private static final LocalDate TUE = LocalDate.of(2026, 9, 15);

    private final TradingDayRepository days = mock(TradingDayRepository.class);

    private SettledCutoff at(String localEt) {
        Clock clock = Clock.fixed(ZonedDateTime.of(LocalDateTime.parse(localEt), ET).toInstant(), ZoneOffset.UTC);
        return new SettledCutoff(days, ET, clock);
    }

    private static DailyBar bar(LocalDate d) {
        BigDecimal p = BigDecimal.TEN;
        return new DailyBar(Instrument.us("AAPL"), d, p, p, p, p, p, 1, p, p, p, p, false);
    }

    @Test
    void 盘中截止到上一个交易日_收盘落定后才含当天() {
        when(days.between(eq(Market.US), any(), any())).thenReturn(List.of(FRI, MON, TUE));

        assertThat(at("2026-09-15T11:00").current()).isEqualTo(MON);
        assertThat(at("2026-09-15T16:10").current()).isEqualTo(MON);
        assertThat(at("2026-09-15T17:30").current()).isEqualTo(TUE);
        assertThat(at("2026-09-13T12:00").current()).as("周日").isEqualTo(FRI);
    }

    @Test
    void 日历为空时至少不含今天没落定的那根() {
        when(days.between(eq(Market.US), any(), any())).thenReturn(List.of());

        assertThat(at("2026-09-15T11:00").current()).isEqualTo(MON);
        assertThat(at("2026-09-15T17:30").current()).isEqualTo(TUE);
    }

    @Test
    void 丢弃晚于截止日的K线() {
        List<DailyBar> kept = SettledCutoff.settled(List.of(bar(FRI), bar(MON), bar(TUE)), MON);

        assertThat(kept).extracting(DailyBar::tradeDate).containsExactly(FRI, MON);
    }
}
