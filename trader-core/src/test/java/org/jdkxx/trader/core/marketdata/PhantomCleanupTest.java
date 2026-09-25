package org.jdkxx.trader.core.marketdata;

import org.jdkxx.trader.core.marketdata.bars.CalendarBackfillService;
import org.jdkxx.trader.core.marketdata.bars.DailyIncrementService;
import org.jdkxx.trader.core.marketdata.bars.DeepBackfillService;
import org.jdkxx.trader.core.marketdata.bars.RotationRefresher;
import org.jdkxx.trader.core.marketdata.jobs.JobService;
import org.jdkxx.trader.core.marketdata.universe.UniverseScope;
import org.jdkxx.trader.core.marketdata.universe.UniverseSyncService;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.SecurityType;
import org.jdkxx.trader.gateway.MarketDataGateway;
import org.jdkxx.trader.storage.marketdata.BarSyncStateRepository;
import org.jdkxx.trader.storage.marketdata.DailyBarRepository;
import org.jdkxx.trader.storage.marketdata.IndexConstituentRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 幽灵 K 线订正的三个数字必须一个口径。
 *
 * <p>3.1.1 前是三个口径（2026-09-25 全项目审查发现）：审计按 {@code phantomBars(20).size()} 报条数、
 * 订正试跑按 {@code phantomBars(200).size()} 列清单、删除却按条件<b>全删</b>。
 * 于是"试跑给你看 200 条、点下去删掉几千条"是可能的，而删 K 线要重新回补才能恢复，
 * 回补又会把券商的脏 K 线拉回来。日历本身坏掉时全库都会被判成幽灵，正是这条路径最危险的时候。
 */
class PhantomCleanupTest {

    private final DailyBarRepository bars = mock(DailyBarRepository.class);
    private final InstrumentRepository instruments = mock(InstrumentRepository.class);

    private MarketDataFacade facade() {
        when(instruments.findById(anyLong())).thenAnswer(inv -> Optional.of(
                new InstrumentRow(inv.getArgument(0), Market.US, "SPY", "SPY", null, SecurityType.ETF,
                        1, null, false, null, 1L, "RESOLVED")));
        return new MarketDataFacade(TestProperties.defaults(), mock(JobService.class), mock(UniverseSyncService.class),
                mock(UniverseScope.class), mock(RotationRefresher.class), mock(DeepBackfillService.class),
                mock(DailyIncrementService.class), mock(CalendarBackfillService.class), mock(TradingDayRepository.class),
                instruments, mock(IndexConstituentRepository.class), bars, mock(BarSyncStateRepository.class),
                mock(MarketDataGateway.class), mock(InstrumentDirectory.class));
    }

    private static List<DailyBarRepository.PhantomBar> phantoms(int n) {
        List<DailyBarRepository.PhantomBar> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new DailyBarRepository.PhantomBar(1L, LocalDate.of(2011, 7, 4).plusDays(i),
                    BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, 0L, BigDecimal.ZERO));
        }
        return out;
    }

    /** 要害：总数几千条时，订正一次只删列出来的那 200 条，绝不静默多删。 */
    @Test
    void 总数远大于批量时_只删列出来的那些() {
        List<DailyBarRepository.PhantomBar> listed = phantoms(DailyBarRepository.PHANTOM_BATCH);
        when(bars.phantomBarCount()).thenReturn(5000);
        when(bars.phantomBars(anyInt())).thenReturn(listed);
        when(bars.deletePhantomBars(listed)).thenReturn(listed.size());

        MarketDataFacade.PhantomCleanup r = facade().cleanupPhantomBars(true);

        assertThat(r.found()).as("总数照实报，不被批量上限截断").isEqualTo(5000);
        assertThat(r.listed()).isEqualTo(200);
        assertThat(r.deleted()).as("只删列出来的那些").isEqualTo(200);
        assertThat(r.remaining()).isEqualTo(4800);
        assertThat(r.bars()).hasSize(200);
        verify(bars).deletePhantomBars(listed);
    }

    @Test
    void 试跑不删任何东西() {
        when(bars.phantomBarCount()).thenReturn(3);
        when(bars.phantomBars(anyInt())).thenReturn(phantoms(3));

        MarketDataFacade.PhantomCleanup r = facade().cleanupPhantomBars(false);

        assertThat(r.applied()).isFalse();
        assertThat(r.found()).isEqualTo(3);
        assertThat(r.listed()).isEqualTo(3);
        assertThat(r.deleted()).isZero();
        assertThat(r.remaining()).as("一条没删，还剩全部").isEqualTo(3);
        verify(bars, never()).deletePhantomBars(org.mockito.ArgumentMatchers.anyList());
    }

    /** 实际情况（生产历史上就 3 根）：一轮删完，remaining 归零。 */
    @Test
    void 一轮删完时remaining归零() {
        List<DailyBarRepository.PhantomBar> listed = phantoms(3);
        when(bars.phantomBarCount()).thenReturn(3);
        when(bars.phantomBars(anyInt())).thenReturn(listed);
        when(bars.deletePhantomBars(listed)).thenReturn(3);

        MarketDataFacade.PhantomCleanup r = facade().cleanupPhantomBars(true);

        assertThat(r.deleted()).isEqualTo(3);
        assertThat(r.remaining()).isZero();
    }

    /** 没有幽灵时不该发删除语句。 */
    @Test
    void 没有幽灵时不发删除() {
        when(bars.phantomBarCount()).thenReturn(0);
        when(bars.phantomBars(anyInt())).thenReturn(List.of());

        MarketDataFacade.PhantomCleanup r = facade().cleanupPhantomBars(true);

        assertThat(r.found()).isZero();
        assertThat(r.deleted()).isZero();
        assertThat(r.bars()).isEmpty();
    }
}
