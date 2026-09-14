package org.jdkxx.trader.core.marketdata.fundamentals;

import org.jdkxx.trader.core.marketdata.Jobs;
import org.jdkxx.trader.core.marketdata.TestProperties;
import org.jdkxx.trader.core.marketdata.jobs.JobContext;
import org.jdkxx.trader.core.marketdata.jobs.JobService;
import org.jdkxx.trader.core.marketdata.universe.UniverseScope;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.SecurityType;
import org.jdkxx.trader.gateway.MarketDataGateway;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;
import org.jdkxx.trader.storage.marketdata.ValuationRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 估值快照只在收盘窗口里取。2.0.2 前按"应有收盘 K 的交易日"定日期，
 * 盘中手工刷新会拿实时价覆盖上一交易日按收盘算的那一行。
 */
class ValuationSnapshotServiceTest {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    private final UniverseScope scope = mock(UniverseScope.class);
    private final MarketDataGateway gateway = mock(MarketDataGateway.class);
    private final ValuationRepository valuations = mock(ValuationRepository.class);
    private final TradingDayRepository days = mock(TradingDayRepository.class);

    private final JobContext ctx = new JobContext() {
        @Override
        public long id() {
            return 1;
        }

        @Override
        public void progress(String text) {
        }

        @Override
        public void partial(String reason) {
        }

        @Override
        public boolean cancelled() {
            return false;
        }
    };

    private ValuationSnapshotService at(String localEt) {
        when(days.isTradingDay(eq(Market.US), any())).thenAnswer(inv -> {
            DayOfWeek w = inv.<LocalDate>getArgument(1).getDayOfWeek();
            return w != DayOfWeek.SATURDAY && w != DayOfWeek.SUNDAY;
        });
        when(scope.universe()).thenReturn(List.of(
                new InstrumentRow(1, Market.US, "AAPL", "AAPL", null, SecurityType.STOCK, 1, null, false, null, 1L, "RESOLVED")));
        when(scope.poolAndHoldings()).thenReturn(List.of());
        when(gateway.snapshots(anyList())).thenReturn(CompletableFuture.completedFuture(List.of()));
        Clock clock = Clock.fixed(ZonedDateTime.of(LocalDateTime.parse(localEt), ET).toInstant(), ZoneOffset.UTC);
        return new ValuationSnapshotService(TestProperties.defaults(), scope, gateway, valuations, days, clock);
    }

    @Test
    void 盘中不取快照也不写库() throws Exception {
        String summary = at("2026-09-15T11:00").run(ctx);

        assertThat(summary).contains("不在估值快照窗口");
        verifyNoInteractions(gateway, valuations);
    }

    @Test
    void 收盘后写到当天() throws Exception {
        at("2026-09-14T17:40").run(ctx);

        verify(valuations).upsertAll(anyMap(), anyList(), eq(LocalDate.of(2026, 9, 14)));
    }

    @Test
    void 次日凌晨补跑仍写到前一个交易日() throws Exception {
        at("2026-09-15T02:00").run(ctx);

        verify(valuations).upsertAll(anyMap(), anyList(), eq(LocalDate.of(2026, 9, 14)));
    }

    @Test
    void 窗口外手工刷新直接冲突_不提交作业() {
        ValuationSnapshotService valuation = mock(ValuationSnapshotService.class);
        when(valuation.asOfDate()).thenReturn(Optional.empty());
        JobService jobs = mock(JobService.class);
        FundamentalsFacade facade = new FundamentalsFacade(jobs, valuation, null, null, null);

        assertThatThrownBy(() -> facade.refreshValuation("MANUAL"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("估值快照窗口");
        verifyNoInteractions(jobs);
    }

    @Test
    void 定时触发不在门面拦截_由作业体跳过() {
        ValuationSnapshotService valuation = mock(ValuationSnapshotService.class);
        when(valuation.asOfDate()).thenReturn(Optional.empty());
        JobService jobs = mock(JobService.class);

        new FundamentalsFacade(jobs, valuation, null, null, null).refreshValuation("SCHEDULE");

        verify(jobs).submit(eq(Jobs.VALUATION_SNAPSHOT), eq("SCHEDULE"), any());
    }
}
