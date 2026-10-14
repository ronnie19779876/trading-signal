package org.jdkxx.trader.core.marketdata;

import org.jdkxx.trader.core.marketdata.bars.CalendarBackfillService;
import org.jdkxx.trader.core.marketdata.bars.DailyIncrementService;
import org.jdkxx.trader.core.marketdata.bars.DeepBackfillService;
import org.jdkxx.trader.core.marketdata.bars.RotationRefresher;
import org.jdkxx.trader.core.marketdata.jobs.JobService;
import org.jdkxx.trader.core.marketdata.universe.UniverseScope;
import org.jdkxx.trader.core.marketdata.universe.UniverseSyncService;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.PoolRole;
import org.jdkxx.trader.domain.SecurityType;
import org.jdkxx.trader.gateway.MarketDataGateway;
import org.jdkxx.trader.storage.marketdata.BarSyncState;
import org.jdkxx.trader.storage.marketdata.BarSyncStateRepository;
import org.jdkxx.trader.storage.marketdata.DailyBarRepository;
import org.jdkxx.trader.storage.marketdata.IndexConstituentRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 标的列表的覆盖区间与条数必须取自 daily_bar 的真实统计。
 *
 * bar_sync_state.bar_count 存的是"最近一次同步写入的条数"（每次覆盖写），每日增量固定每只回拉 6 根，
 * 所以 3.0.6 及以前所有标的都显示 6（2026-09-19 实测 AAPL 显示 6、实际 5051 根）。
 */
class InstrumentViewCoverageTest {

    private static final long ID = 7L;
    private static final InstrumentRow AAPL = new InstrumentRow(ID, Market.US, "AAPL", "Apple", "苹果", SecurityType.STOCK,
            1, LocalDate.of(1980, 12, 12), false, "NASDAQ", 1L, "RESOLVED");

    private final InstrumentRepository instruments = mock(InstrumentRepository.class);
    private final IndexConstituentRepository constituents = mock(IndexConstituentRepository.class);
    private final DailyBarRepository bars = mock(DailyBarRepository.class);
    private final BarSyncStateRepository states = mock(BarSyncStateRepository.class);
    private final UniverseScope scope = mock(UniverseScope.class);
    private final InstrumentDirectory directory = mock(InstrumentDirectory.class);

    private final MarketDataFacade facade = new MarketDataFacade(mock(MarketDataProperties.class), mock(JobService.class),
            mock(UniverseSyncService.class), scope, mock(RotationRefresher.class), mock(DeepBackfillService.class),
            mock(DailyIncrementService.class), mock(CalendarBackfillService.class), mock(TradingDayRepository.class),
            instruments, constituents, bars, states, mock(MarketDataGateway.class), directory);

    /** 同步状态里是"最近写入 6 根、区间到 09-11"，真实数据是 5051 根、到 09-18。 */
    private void given(DailyBarRepository.InstrumentCoverage coverage) {
        when(instruments.findAll()).thenReturn(List.of(AAPL));
        when(constituents.currentAll()).thenReturn(List.of());
        when(scope.roles()).thenReturn(Map.of(ID, PoolRole.POOL));
        BarSyncState stale = new BarSyncState(ID, BarSyncState.DEPTH_HIST,
                LocalDate.of(2006, 8, 21), LocalDate.of(2026, 9, 11), 6, null, null, null, null);
        when(states.findAll()).thenReturn(List.of(stale));
        when(states.find(ID)).thenReturn(java.util.Optional.of(stale));
        when(directory.require("AAPL")).thenReturn(AAPL);
        when(bars.coverageByInstrument()).thenReturn(coverage == null ? List.of() : List.of(coverage));
        when(bars.coverage(ID)).thenReturn(java.util.Optional.ofNullable(coverage));
    }

    @Test
    void 列表的条数与区间取自日K真实统计_不是同步状态的最近写入条数() {
        given(new DailyBarRepository.InstrumentCoverage(ID, 5051, LocalDate.of(2006, 8, 21), LocalDate.of(2026, 9, 18)));

        MarketDataFacade.InstrumentView v = facade.universe(null, null).getFirst();

        assertThat(v.barCount()).isEqualTo(5051);
        assertThat(v.latest()).isEqualTo(LocalDate.of(2026, 9, 18));
        assertThat(v.depth()).isEqualTo(BarSyncState.DEPTH_HIST);   // 深度仍来自同步状态
    }

    @Test
    void 单只查询同样取真实统计() {
        given(new DailyBarRepository.InstrumentCoverage(ID, 68, LocalDate.of(2026, 6, 12), LocalDate.of(2026, 9, 18)));

        MarketDataFacade.InstrumentView v = facade.instrument("AAPL");

        assertThat(v.barCount()).isEqualTo(68);
        assertThat(v.earliest()).isEqualTo(LocalDate.of(2026, 6, 12));
    }

    @Test
    void 一根K线都没有时条数为0_区间为空() {
        given(null);

        MarketDataFacade.InstrumentView v = facade.universe(null, null).getFirst();

        assertThat(v.barCount()).isZero();
        assertThat(v.earliest()).isNull();
        assertThat(v.latest()).isNull();
    }

    /**
     * /api/pool（3.4.3）：只取池成员、只统计它们的 K 线覆盖——原先三种角色各调一次 universe()，每次对整张日 K 表 GROUP BY。
     * **每种角色都要返回**（遍历 PoolRole.values() 造数据，加新角色自动覆盖；当初 BENCHMARK 就漏过一次），
     * 顺序与原先「按角色声明顺序拼起来、角色内按代码」相同。
     */
    @Test
    void 池列表覆盖全部角色_只统计池成员_按角色再按代码排序() {
        Map<Long, PoolRole> roles = new java.util.HashMap<>();
        List<InstrumentRow> rows = new java.util.ArrayList<>();
        long id = 100;
        for (PoolRole role : PoolRole.values()) {
            for (String sym : List.of("Z" + role.name(), "A" + role.name())) {      // 角色内故意倒序给，看排序
                rows.add(new InstrumentRow(id, Market.US, sym, sym, null, SecurityType.STOCK, 1, null, false, "NYSE", id, "RESOLVED"));
                roles.put(id++, role);
            }
        }
        when(scope.roles()).thenReturn(roles);
        when(constituents.currentAll()).thenReturn(List.of());
        when(states.findAll()).thenReturn(List.of());
        when(instruments.findByIds(roles.keySet())).thenReturn(rows);
        when(bars.coverageByInstrument(roles.keySet())).thenReturn(List.of(
                new DailyBarRepository.InstrumentCoverage(100L, 100, LocalDate.of(2020, 1, 2), LocalDate.of(2026, 10, 13))));

        List<MarketDataFacade.InstrumentView> pool = facade.pool();

        List<String> expected = java.util.Arrays.stream(PoolRole.values())
                .flatMap(r -> java.util.stream.Stream.of("A" + r.name(), "Z" + r.name())).toList();
        assertThat(pool).extracting(MarketDataFacade.InstrumentView::symbol).containsExactlyElementsOf(expected);
        assertThat(pool).extracting(MarketDataFacade.InstrumentView::role).containsAll(
                java.util.Arrays.stream(PoolRole.values()).map(PoolRole::name).toList());
        assertThat(pool.stream().filter(v -> v.barCount() == 100).count()).isEqualTo(1);
        org.mockito.Mockito.verify(bars, org.mockito.Mockito.never()).coverageByInstrument();
        org.mockito.Mockito.verify(instruments, org.mockito.Mockito.never()).findAll();
    }
}
