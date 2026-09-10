package org.jdkxx.trader.core.marketdata.fundamentals;

import org.jdkxx.trader.core.marketdata.MarketDataProperties;
import org.jdkxx.trader.core.marketdata.TestProperties;
import org.jdkxx.trader.core.marketdata.audit.BarAuditService;
import org.jdkxx.trader.core.marketdata.universe.UniverseScope;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.SecurityType;
import org.jdkxx.trader.storage.marketdata.FinancialRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.jdkxx.trader.storage.marketdata.JobRunRepository;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;
import org.jdkxx.trader.storage.marketdata.ValuationRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FundamentalsAuditServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 8);
    private static final LocalDate HOLIDAY = LocalDate.of(2026, 9, 7);

    private static MarketDataProperties props() {
        return TestProperties.defaults();
    }

    private static InstrumentRow row(long id, String symbol) {
        return new InstrumentRow(id, Market.US, symbol, symbol, null, SecurityType.STOCK, 1, null, false, null, 1L, "RESOLVED");
    }

    @Test
    void 休市日直接判过_不查估值() {
        TradingDayRepository days = mock(TradingDayRepository.class);
        when(days.covers(eq(Market.US), any())).thenReturn(true);
        when(days.isTradingDay(Market.US, HOLIDAY)).thenReturn(false);
        ValuationRepository valuations = mock(ValuationRepository.class);
        UniverseScope scope = mock(UniverseScope.class);

        BarAuditService.Report r = new FundamentalsAuditService(props(), scope, valuations,
                mock(FinancialRepository.class), days, mock(JobRunRepository.class), Clock.systemUTC()).audit(HOLIDAY);

        assertThat(r.ok()).isTrue();
        assertThat(r.summary()).containsEntry("tradingDay", false);
        verifyNoInteractions(valuations, scope);
    }

    @Test
    void 负市盈率与ETF缺市值都不判失败() {
        TradingDayRepository days = mock(TradingDayRepository.class);
        when(days.covers(eq(Market.US), any())).thenReturn(true);
        when(days.isTradingDay(Market.US, DAY)).thenReturn(true);
        UniverseScope scope = mock(UniverseScope.class);
        when(scope.universe()).thenReturn(List.of(row(1, "NVDA"), row(2, "SPY")));
        when(scope.poolAndHoldings()).thenReturn(List.of(row(1, "NVDA")));
        ValuationRepository valuations = mock(ValuationRepository.class);
        when(valuations.instrumentIdsOn(DAY)).thenReturn(Set.of(1L, 2L));
        // 2 行里 1 只负市盈率、1 只没有市值（ETF）——都属正常
        when(valuations.statsOn(DAY)).thenReturn(new ValuationRepository.DayStats(2, 0, 1, 1));
        FinancialRepository financials = mock(FinancialRepository.class);
        when(financials.latestPeriods()).thenReturn(List.of(new FinancialRepository.Latest(1, "MAIN_INDEX", DAY.minusDays(40))));
        when(financials.countReports()).thenReturn(48L);
        JobRunRepository jobs = mock(JobRunRepository.class);
        when(jobs.latestOf(any())).thenReturn(Optional.empty());

        BarAuditService.Report r = new FundamentalsAuditService(props(), scope, valuations, financials, days, jobs,
                Clock.systemUTC()).audit(DAY);

        assertThat(r.ok()).as("负市盈率和 ETF 无市值都不该判失败").isTrue();
        assertThat(r.summary()).containsEntry("negativePe", 1L);
        assertThat(r.checks()).extracting(BarAuditService.Check::name)
                .contains("valuationCompleteness", "valuationSanity", "financialsFreshness", "valuationJob");
    }

    @Test
    void 缺估值快照是关键失败() {
        TradingDayRepository days = mock(TradingDayRepository.class);
        when(days.covers(eq(Market.US), any())).thenReturn(true);
        when(days.isTradingDay(Market.US, DAY)).thenReturn(true);
        UniverseScope scope = mock(UniverseScope.class);
        when(scope.universe()).thenReturn(List.of(row(1, "NVDA"), row(2, "AAPL")));
        when(scope.poolAndHoldings()).thenReturn(List.of());
        ValuationRepository valuations = mock(ValuationRepository.class);
        when(valuations.instrumentIdsOn(DAY)).thenReturn(Set.of(1L));
        when(valuations.statsOn(DAY)).thenReturn(new ValuationRepository.DayStats(1, 0, 0, 0));
        FinancialRepository financials = mock(FinancialRepository.class);
        when(financials.latestPeriods()).thenReturn(List.of());
        JobRunRepository jobs = mock(JobRunRepository.class);
        when(jobs.latestOf(any())).thenReturn(Optional.empty());

        BarAuditService.Report r = new FundamentalsAuditService(props(), scope, valuations, financials, days, jobs,
                Clock.systemUTC()).audit(DAY);

        assertThat(r.ok()).isFalse();
        BarAuditService.Check c = r.checks().stream().filter(x -> x.name().equals("valuationCompleteness")).findFirst().orElseThrow();
        assertThat(c.ok()).isFalse();
        assertThat(c.samples()).containsExactly("AAPL");
    }

    @Test
    void 从来没有财报的标的不算过旧_只在说明里提一句() {
        // SPY 这类真基金没有财务报表；REITs 虽然也被富途归为 Trust，但取得到财报，会走上面的路径
        TradingDayRepository days = mock(TradingDayRepository.class);
        when(days.covers(eq(Market.US), any())).thenReturn(true);
        when(days.isTradingDay(Market.US, DAY)).thenReturn(true);
        UniverseScope scope = mock(UniverseScope.class);
        when(scope.universe()).thenReturn(List.of(row(1, "NVDA")));
        when(scope.poolAndHoldings()).thenReturn(List.of(row(1, "NVDA"), row(2, "SPY")));
        ValuationRepository valuations = mock(ValuationRepository.class);
        when(valuations.instrumentIdsOn(DAY)).thenReturn(Set.of(1L, 2L));
        when(valuations.statsOn(DAY)).thenReturn(new ValuationRepository.DayStats(2, 0, 1, 0));
        FinancialRepository financials = mock(FinancialRepository.class);
        when(financials.latestPeriods()).thenReturn(List.of(
                new FinancialRepository.Latest(1, "INCOME", DAY.minusDays(40))));
        JobRunRepository jobs = mock(JobRunRepository.class);
        when(jobs.latestOf(any())).thenReturn(Optional.empty());

        BarAuditService.Report r = new FundamentalsAuditService(props(), scope, valuations, financials, days, jobs,
                Clock.systemUTC()).audit(DAY);

        BarAuditService.Check c = r.checks().stream().filter(x -> x.name().equals("financialsFreshness")).findFirst().orElseThrow();
        assertThat(c.ok()).as("没有财报不等于过旧").isTrue();
        assertThat(c.detail()).contains("SPY").contains("基金正常没有");
        assertThat(r.summary()).containsEntry("poolWithoutReports", 1);
    }

    @Test
    void 财报过旧只是提示不影响总判定() {
        TradingDayRepository days = mock(TradingDayRepository.class);
        when(days.covers(eq(Market.US), any())).thenReturn(true);
        when(days.isTradingDay(Market.US, DAY)).thenReturn(true);
        UniverseScope scope = mock(UniverseScope.class);
        when(scope.universe()).thenReturn(List.of(row(1, "NVDA")));
        when(scope.poolAndHoldings()).thenReturn(List.of(row(1, "NVDA")));
        ValuationRepository valuations = mock(ValuationRepository.class);
        when(valuations.instrumentIdsOn(DAY)).thenReturn(Set.of(1L));
        when(valuations.statsOn(DAY)).thenReturn(new ValuationRepository.DayStats(1, 0, 0, 0));
        FinancialRepository financials = mock(FinancialRepository.class);
        when(financials.latestPeriods()).thenReturn(List.of(
                new FinancialRepository.Latest(1, "INCOME", DAY.minusDays(400))));
        JobRunRepository jobs = mock(JobRunRepository.class);
        when(jobs.latestOf(any())).thenReturn(Optional.empty());

        BarAuditService.Report r = new FundamentalsAuditService(props(), scope, valuations, financials, days, jobs,
                Clock.systemUTC()).audit(DAY);

        assertThat(r.ok()).as("陈旧度是提示项").isTrue();
        BarAuditService.Check c = r.checks().stream().filter(x -> x.name().equals("financialsFreshness")).findFirst().orElseThrow();
        assertThat(c.ok()).isFalse();
        assertThat(c.critical()).isFalse();
        assertThat(c.samples()).hasSize(1);
    }
}
