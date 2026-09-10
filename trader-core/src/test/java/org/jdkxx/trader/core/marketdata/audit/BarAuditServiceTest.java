package org.jdkxx.trader.core.marketdata.audit;

import org.jdkxx.trader.core.marketdata.MarketDataProperties;
import org.jdkxx.trader.core.marketdata.universe.UniverseScope;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.SecurityType;
import org.jdkxx.trader.storage.marketdata.BarSyncStateRepository;
import org.jdkxx.trader.storage.marketdata.DailyBarRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.jdkxx.trader.storage.marketdata.JobRunRepository;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BarAuditServiceTest {

    private static final LocalDate LABOR_DAY = LocalDate.of(2026, 9, 7);

    private static InstrumentRow row(long id, String symbol) {
        return new InstrumentRow(id, Market.US, symbol, symbol, null, SecurityType.STOCK, 1, null, false, null, 1L, "RESOLVED");
    }

    private static MarketDataProperties props() {
        return new MarketDataProperties(null, null, new MarketDataProperties.Refresh(90, 65, true, 1000, 5),
                null, null, null, null, false, "", "", "", "America/New_York");
    }

    @Test
    void 休市日直接判通过_不去查K线() {
        TradingDayRepository days = mock(TradingDayRepository.class);
        when(days.covers(eq(Market.US), any())).thenReturn(true);
        when(days.isTradingDay(Market.US, LABOR_DAY)).thenReturn(false);
        DailyBarRepository bars = mock(DailyBarRepository.class);
        UniverseScope scope = mock(UniverseScope.class);

        BarAuditService s = new BarAuditService(props(), scope, bars, days,
                mock(BarSyncStateRepository.class), mock(JobRunRepository.class), null, Clock.systemUTC());
        BarAuditService.Report r = s.audit(LABOR_DAY);

        assertThat(r.ok()).isTrue();
        assertThat(r.date()).isEqualTo(LABOR_DAY);
        assertThat(r.summary()).containsEntry("tradingDay", false);
        assertThat(r.checks()).singleElement().satisfies(c -> {
            assertThat(c.name()).isEqualTo("calendar");
            assertThat(c.detail()).contains("休市");
        });
        verifyNoInteractions(bars, scope);
    }

    @Test
    void 日历覆盖不到的日期照常审计() {
        TradingDayRepository days = mock(TradingDayRepository.class);
        when(days.covers(eq(Market.US), any())).thenReturn(false);
        DailyBarRepository bars = mock(DailyBarRepository.class);
        when(bars.instrumentIdsWithBarOn(any())).thenReturn(Set.of());
        when(bars.sanityOn(any())).thenReturn(new DailyBarRepository.DaySanity(0, 0, 0, 0, 0, 0));
        when(bars.continuityIssues(any(), any(), anyInt())).thenReturn(List.of());
        when(bars.coverageByInstrument()).thenReturn(List.of());
        when(days.coverage(Market.US)).thenReturn(
                new TradingDayRepository.Coverage(LocalDate.of(2006, 8, 21), LocalDate.of(2026, 9, 18), 5000, 2591, 2409));
        UniverseScope scope = mock(UniverseScope.class);
        when(scope.universe()).thenReturn(List.of());
        when(scope.poolAndHoldings()).thenReturn(List.of());
        BarSyncStateRepository states = mock(BarSyncStateRepository.class);
        when(states.findAll()).thenReturn(List.of());
        JobRunRepository jobs = mock(JobRunRepository.class);
        when(jobs.latestOf(any())).thenReturn(Optional.empty());

        BarAuditService s = new BarAuditService(props(), scope, bars, days, states, jobs, null, Clock.systemUTC());
        BarAuditService.Report r = s.audit(LocalDate.of(2010, 1, 4));

        assertThat(r.summary()).containsEntry("tradingDay", true);
        assertThat(r.checks()).extracting(BarAuditService.Check::name).contains("completeness", "sanity", "continuity", "calendarCoverage");
    }

    @Test
    void 日历没覆盖到最早K线时给出提示但不判失败() {
        TradingDayRepository days = mock(TradingDayRepository.class);
        when(days.covers(eq(Market.US), any())).thenReturn(false);
        // 日历只到 2016，深度标的的 K 线却从 2006 就有 —— 说明反推段没跑
        when(days.coverage(Market.US)).thenReturn(
                new TradingDayRepository.Coverage(LocalDate.of(2016, 9, 12), LocalDate.of(2026, 9, 18), 2591, 2591, 0));
        DailyBarRepository bars = mock(DailyBarRepository.class);
        when(bars.instrumentIdsWithBarOn(any())).thenReturn(Set.of(1L));
        when(bars.sanityOn(any())).thenReturn(new DailyBarRepository.DaySanity(1, 0, 0, 0, 0, 0));
        when(bars.continuityIssues(any(), any(), anyInt())).thenReturn(List.of());
        when(bars.coverageByInstrument()).thenReturn(List.of(
                new DailyBarRepository.InstrumentCoverage(1L, 5000, LocalDate.of(2006, 8, 21), LocalDate.of(2026, 9, 9))));
        UniverseScope scope = mock(UniverseScope.class);
        when(scope.universe()).thenReturn(List.of(row(1, "SPY")));
        when(scope.poolAndHoldings()).thenReturn(List.of(row(1, "SPY")));
        BarSyncStateRepository states = mock(BarSyncStateRepository.class);
        when(states.findAll()).thenReturn(List.of());
        JobRunRepository jobs = mock(JobRunRepository.class);
        when(jobs.latestOf(any())).thenReturn(Optional.empty());

        BarAuditService.Report r = new BarAuditService(props(), scope, bars, days, states, jobs, null, Clock.systemUTC())
                .audit(LocalDate.of(2026, 9, 9));

        BarAuditService.Check c = r.checks().stream().filter(x -> x.name().equals("calendarCoverage")).findFirst().orElseThrow();
        assertThat(c.ok()).isFalse();
        assertThat(c.critical()).as("日历不全不影响当日数据正确性").isFalse();
        assertThat(c.detail()).contains("2006-08-21").contains("日历回补");
    }
}
