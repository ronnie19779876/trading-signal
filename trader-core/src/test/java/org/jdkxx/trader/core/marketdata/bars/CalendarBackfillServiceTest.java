package org.jdkxx.trader.core.marketdata.bars;

import org.jdkxx.trader.core.marketdata.MarketDataProperties;
import org.jdkxx.trader.core.marketdata.TestProperties;
import org.jdkxx.trader.core.marketdata.jobs.JobContext;
import org.jdkxx.trader.core.marketdata.universe.UniverseScope;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.SecurityType;
import org.jdkxx.trader.domain.TradingDay;
import org.jdkxx.trader.gateway.MarketDataGateway;
import org.jdkxx.trader.storage.marketdata.DailyBarRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CalendarBackfillServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-10T12:00:00Z"), ZoneId.of("UTC"));
    private static final LocalDate BROKER_EARLIEST = LocalDate.of(2016, 9, 12);

    private static MarketDataProperties props() {
        return TestProperties.defaults();
    }

    private static InstrumentRow row(long id, String symbol) {
        return new InstrumentRow(id, Market.US, symbol, symbol, null, SecurityType.STOCK, 1, null, false, null, 1L, "RESOLVED");
    }

    private static JobContext ctx() {
        JobContext c = mock(JobContext.class);
        when(c.cancelled()).thenReturn(false);
        return c;
    }

    private static List<TradingDay> brokerDays() {
        return List.of(new TradingDay(Market.US, BROKER_EARLIEST, 0),
                new TradingDay(Market.US, LocalDate.of(2026, 9, 9), 0));
    }

    @Test
    void 券商段之前的日期才反推_且只补空缺() throws Exception {
        MarketDataGateway gateway = mock(MarketDataGateway.class);
        when(gateway.tradingDays(eq(Market.US), any(), any())).thenReturn(CompletableFuture.completedFuture(brokerDays()));
        TradingDayRepository days = mock(TradingDayRepository.class);
        when(days.coverage(Market.US)).thenReturn(
                new TradingDayRepository.Coverage(LocalDate.of(2006, 8, 21), LocalDate.of(2026, 9, 18), 5051, 2519, 2532));
        DailyBarRepository bars = mock(DailyBarRepository.class);
        when(bars.coverageByInstrument()).thenReturn(List.of(
                new DailyBarRepository.InstrumentCoverage(1L, 5000, LocalDate.of(2006, 8, 21), LocalDate.of(2026, 9, 9))));
        when(bars.distinctTradeDates(any(), any(), any(), anyInt()))
                .thenReturn(List.of(LocalDate.of(2006, 8, 21), LocalDate.of(2006, 8, 22)));
        when(days.insertDerived(eq(Market.US), any())).thenReturn(2);
        UniverseScope scope = mock(UniverseScope.class);
        when(scope.poolAndHoldings()).thenReturn(List.of(row(1, "SPY")));

        String summary = new CalendarBackfillService(props(), gateway, days, bars, scope, CLOCK).run(ctx());

        ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> to = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<Integer> min = ArgumentCaptor.forClass(Integer.class);
        verify(bars).distinctTradeDates(any(), from.capture(), to.capture(), min.capture());
        assertThat(from.getValue()).isEqualTo(LocalDate.of(2006, 8, 21));
        assertThat(to.getValue()).as("反推区间的上界是券商段起点，不重叠").isEqualTo(BROKER_EARLIEST);
        assertThat(min.getValue()).as("要求多只同时成交，挡掉券商在假日留的脏 K 线").isGreaterThanOrEqualTo(2);
        assertThat(summary).contains("券商段 2 天").contains("反推新增 2 天");
    }

    @Test
    void 券商段已覆盖到最早K线时不反推() throws Exception {
        MarketDataGateway gateway = mock(MarketDataGateway.class);
        when(gateway.tradingDays(eq(Market.US), any(), any())).thenReturn(CompletableFuture.completedFuture(brokerDays()));
        TradingDayRepository days = mock(TradingDayRepository.class);
        when(days.coverage(Market.US)).thenReturn(
                new TradingDayRepository.Coverage(BROKER_EARLIEST, LocalDate.of(2026, 9, 18), 2519, 2519, 0));
        DailyBarRepository bars = mock(DailyBarRepository.class);
        // 最早 K 线晚于券商段起点：没有需要反推的区间
        when(bars.coverageByInstrument()).thenReturn(List.of(
                new DailyBarRepository.InstrumentCoverage(1L, 100, LocalDate.of(2020, 1, 2), LocalDate.of(2026, 9, 9))));
        UniverseScope scope = mock(UniverseScope.class);
        when(scope.poolAndHoldings()).thenReturn(List.of(row(1, "AAPL")));

        String summary = new CalendarBackfillService(props(), gateway, days, bars, scope, CLOCK).run(ctx());

        verify(bars, never()).distinctTradeDates(any(), any(), any(), anyInt());
        verify(days, never()).insertDerived(any(), any());
        assertThat(summary).contains("反推新增 0 天");
    }
}
