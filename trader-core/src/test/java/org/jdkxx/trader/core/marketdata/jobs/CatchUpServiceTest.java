package org.jdkxx.trader.core.marketdata.jobs;

import org.jdkxx.trader.core.marketdata.TestProperties;
import org.jdkxx.trader.core.marketdata.universe.UniverseScope;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.SecurityType;
import org.jdkxx.trader.storage.marketdata.DailyBarRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;
import org.jdkxx.trader.storage.marketdata.ValuationRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CatchUpServiceTest {

    private static final LocalDate THU = LocalDate.of(2026, 9, 10);
    /** 美东 2026-09-10 21:00（补偿检查的时点），换成 UTC 是次日 01:00。 */
    private static final Clock AT_21_ET = Clock.fixed(Instant.parse("2026-09-11T01:00:00Z"), ZoneId.of("UTC"));

    private static InstrumentRow row(long id, String symbol) {
        return new InstrumentRow(id, Market.US, symbol, symbol, null, SecurityType.STOCK, 1, null, false, null, 1L, "RESOLVED");
    }

    private static CatchUpService service(Set<Long> withBars, Set<Long> withValuation, List<LocalDate> calendar) {
        TradingDayRepository days = mock(TradingDayRepository.class);
        when(days.between(eq(Market.US), any(), any())).thenReturn(calendar);
        DailyBarRepository bars = mock(DailyBarRepository.class);
        when(bars.instrumentIdsWithBarOn(any())).thenReturn(withBars);
        ValuationRepository valuations = mock(ValuationRepository.class);
        when(valuations.instrumentIdsOn(any())).thenReturn(withValuation);
        UniverseScope scope = mock(UniverseScope.class);
        when(scope.universe()).thenReturn(List.of(row(1, "AAPL"), row(2, "MSFT")));
        when(scope.poolAndHoldings()).thenReturn(List.of(row(1, "AAPL")));
        return new CatchUpService(TestProperties.defaults(), scope, bars, valuations, days, AT_21_ET);
    }

    @Test
    void 数据齐了什么都不补() {
        CatchUpService.Gap gap = service(Set.of(1L, 2L), Set.of(1L, 2L), List.of(THU)).check();

        assertThat(gap.tradingDay()).isTrue();
        assertThat(gap.expected()).isEqualTo(THU);
        assertThat(gap.barsMissing()).isFalse();
        assertThat(gap.valuationMissing()).isFalse();
    }

    @Test
    void 估值缺了要补_这正是要救的场景() {
        // 增量跑成功但估值被挡掉：券商快照接口不接受日期，过了次日盘前就永远补不回来
        CatchUpService.Gap gap = service(Set.of(1L, 2L), Set.of(), List.of(THU)).check();

        assertThat(gap.barsMissing()).isFalse();
        assertThat(gap.valuationMissing()).isTrue();
        assertThat(gap.describe()).contains("估值 0");
    }

    @Test
    void K线也缺时两个都补() {
        CatchUpService.Gap gap = service(Set.of(1L), Set.of(), List.of(THU)).check();

        assertThat(gap.barsMissing()).isTrue();
        assertThat(gap.valuationMissing()).isTrue();
    }

    @Test
    void 非交易日不补_周末与假日不该触发补跑() {
        // 日历里没有今天（当天休市），最近交易日是前一天
        CatchUpService.Gap gap = service(Set.of(), Set.of(), List.of(THU.minusDays(1))).check();

        assertThat(gap.tradingDay()).isFalse();
        assertThat(gap.barsMissing()).isFalse();
        assertThat(gap.valuationMissing()).isFalse();
        assertThat(gap.describe()).contains("非交易日");
    }

    @Test
    void 日历为空时不乱补() {
        CatchUpService.Gap gap = service(Set.of(), Set.of(), List.of()).check();

        assertThat(gap.tradingDay()).isFalse();
        assertThat(gap.describe()).contains("交易日历为空");
    }
}
