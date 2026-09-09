package org.jdkxx.trader.core.marketdata.audit;

import org.jdkxx.trader.core.marketdata.MarketDataProperties;
import org.jdkxx.trader.core.marketdata.universe.UniverseScope;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.storage.marketdata.BarSyncStateRepository;
import org.jdkxx.trader.storage.marketdata.DailyBarRepository;
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
        assertThat(r.checks()).extracting(BarAuditService.Check::name).contains("completeness", "sanity", "continuity");
    }
}
