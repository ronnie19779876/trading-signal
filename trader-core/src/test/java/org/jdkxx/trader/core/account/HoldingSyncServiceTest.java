package org.jdkxx.trader.core.account;

import org.jdkxx.trader.core.account.HoldingSyncService.Action;
import org.jdkxx.trader.core.account.HoldingSyncService.Change;
import org.jdkxx.trader.core.account.HoldingSyncService.Held;
import org.jdkxx.trader.core.account.HoldingSyncService.Plan;
import org.jdkxx.trader.core.account.HoldingSyncService.Result;
import org.jdkxx.trader.core.account.ValuedPosition.PriceSource;
import org.jdkxx.trader.core.marketdata.MarketDataFacade;
import org.jdkxx.trader.core.marketdata.PoolService;
import org.jdkxx.trader.domain.AccountKind;
import org.jdkxx.trader.domain.AccountRef;
import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.PoolRole;
import org.jdkxx.trader.domain.Position;
import org.jdkxx.trader.domain.SecurityType;
import org.jdkxx.trader.gateway.AccountGateway;
import org.jdkxx.trader.gateway.BrokerGateway;
import org.jdkxx.trader.storage.marketdata.InstrumentRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.jdkxx.trader.storage.marketdata.JobRunRepository;
import org.jdkxx.trader.storage.marketdata.PoolRepository;
import org.jdkxx.trader.storage.marketdata.PoolRepository.SyncRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 代码、账户全部虚构。 */
class HoldingSyncServiceTest {

    private static final String ACCT = "ACCT-A";

    private final BrokerGateway broker = mock(BrokerGateway.class);
    private final AccountGateway accounts = mock(AccountGateway.class);
    private final InstrumentRepository instruments = mock(InstrumentRepository.class);
    private final PoolRepository pool = mock(PoolRepository.class);
    private final PoolService poolService = mock(PoolService.class);
    private final MarketDataFacade marketData = mock(MarketDataFacade.class);
    private final HoldingSyncService service = new HoldingSyncService(
            new AccountProperties(true, "0 0 18 * * MON-FRI", "test-only-secret-0123456789", List.of("CASHX"),
                    new BigDecimal("0.002"), BigDecimal.ONE),
            null, broker, accounts, instruments, pool, poolService, marketData, mock(JobRunRepository.class));

    @AfterEach
    void tearDown() {
        service.close();
    }

    private static SyncRow member(long id, PoolRole role, String returnRole) {
        return new SyncRow(id, role, "MANUAL", returnRole);
    }

    private static InstrumentRow row(long id, String symbol) {
        return new InstrumentRow(id, Market.US, symbol, symbol, null, SecurityType.STOCK, 1, null, false, null, 1L, "RESOLVED");
    }

    private static Position pos(String symbol, String conId, String type) {
        return new Position(Broker.IBKR, ACCT, conId, symbol, symbol, type, "NYSE", null, "USD", BigDecimal.TEN, null);
    }

    // ------------------------------------------------------------------ 计划（纯计算）

    @Test
    void 持有而池里没有的加入_POOL升HOLDING_基准不动_库里没有的按代码加入() {
        Plan plan = HoldingSyncService.plan(
                List.of(new Held(1L, "AAA"), new Held(2L, "BBB"), new Held(3L, "IDX"), new Held(null, "NEWCO")),
                List.of(member(2, PoolRole.POOL, null), member(3, PoolRole.BENCHMARK, null)),
                Map.of(2L, "BBB", 3L, "IDX"));

        assertThat(plan.blocked()).isNull();
        assertThat(plan.changes()).extracting(Change::action, Change::symbol, Change::instrumentId).containsExactly(
                tuple(Action.ADD, "AAA", 1L), tuple(Action.PROMOTE, "BBB", 2L), tuple(Action.ADD, "NEWCO", null));
        assertThat(plan.untouched()).singleElement().asString().contains("IDX").contains("基准");
    }

    @Test
    void 清仓后原为POOL的回POOL_其余移出池_仍持有的不动() {
        Plan plan = HoldingSyncService.plan(
                List.of(new Held(1L, "AAA")),
                List.of(member(1, PoolRole.HOLDING, null), member(2, PoolRole.HOLDING, "POOL"), member(4, PoolRole.HOLDING, null),
                        member(5, PoolRole.POOL, null)),
                Map.of(1L, "AAA", 2L, "BBB", 4L, "DDD", 5L, "EEE"));

        assertThat(plan.changes()).extracting(Change::action, Change::symbol)
                .containsExactly(tuple(Action.RETURN_TO_POOL, "BBB"), tuple(Action.REMOVE, "DDD"));
    }

    @Test
    void 盈透返回空持仓而池里有HOLDING时不清空() {
        Plan plan = HoldingSyncService.plan(List.of(), List.of(member(1, PoolRole.HOLDING, null)), Map.of(1L, "AAA"));

        assertThat(plan.blocked()).contains("持仓为空").contains("不自动清空");
        assertThat(plan.changes()).isEmpty();
    }

    @Test
    void 只剩现金管理工具时照常清掉HOLDING() {
        // 盈透确实返回了持仓（SGOV），过滤掉现金管理工具后才为空：
        // 2.0.2 前被当成"数据不完整"拦下，HOLDING 永远清不掉，每天快照 PARTIAL，手工 apply=true 也被拦
        Plan plan = HoldingSyncService.plan(List.of(), false, List.of(member(1, PoolRole.HOLDING, null)), Map.of(1L, "AAA"));

        assertThat(plan.blocked()).isNull();
        assertThat(plan.changes()).extracting(Change::action, Change::symbol).containsExactly(tuple(Action.REMOVE, "AAA"));
    }

    @Test
    void 已经一致或都为空时无需变动() {
        assertThat(HoldingSyncService.plan(List.of(new Held(1L, "AAA")), List.of(member(1, PoolRole.HOLDING, null)), Map.of())
                .changes()).isEmpty();
        Plan empty = HoldingSyncService.plan(List.of(), List.of(member(5, PoolRole.POOL, null)), Map.of());
        assertThat(empty.blocked()).isNull();
        assertThat(empty.changes()).isEmpty();
        assertThat(HoldingSyncService.describe(empty, false, List.of())).isEqualTo("无需变动");
    }

    // ------------------------------------------------------------------ 执行

    @Test
    void 试跑只给计划不改池() throws Exception {
        when(broker.accounts()).thenReturn(completedFuture(List.of(new AccountRef(Broker.IBKR, ACCT, AccountKind.LIVE, Set.of(Market.US)))));
        when(accounts.positions(ACCT)).thenReturn(completedFuture(List.of(pos("AAA", "1001", "STK"))));
        when(instruments.findIdByIbkrConId(1001L)).thenReturn(Optional.of(1L));
        when(pool.findAllForSync()).thenReturn(List.of());

        Result r = service.syncNow(false, "MANUAL");

        assertThat(r.applied()).isFalse();
        assertThat(r.summary()).isEqualTo("计划 加入 HOLDING AAA");
        verify(pool, never()).addHolding(anyLong(), any());
        verifyNoInteractions(poolService, marketData);
    }

    @Test
    void 应用时逐项改池_触发订阅对账与深度回补_单处失败不挡其余() throws Exception {
        when(broker.accounts()).thenReturn(completedFuture(List.of(new AccountRef(Broker.IBKR, ACCT, AccountKind.LIVE, Set.of(Market.US)))));
        when(accounts.positions(ACCT)).thenReturn(completedFuture(List.of(
                pos("AAA", "1001", "STK"), pos("NEWCO", "1002", "STK"), pos("CASHX", "1003", "STK"), pos("AAA 261218C1", "1004", "OPT"))));
        when(instruments.findIdByIbkrConId(1001L)).thenReturn(Optional.of(1L));
        when(instruments.find(any())).thenReturn(Optional.empty());
        when(pool.findAllForSync()).thenReturn(List.of(member(5, PoolRole.HOLDING, "POOL")));
        when(instruments.findByIds(any())).thenReturn(List.of(row(5, "EEE")));
        when(pool.addHolding(eq(1L), anyString())).thenReturn(true);
        when(poolService.add(eq("NEWCO"), eq(PoolRole.HOLDING), anyString()))
                .thenThrow(new java.util.NoSuchElementException("标的 NEWCO 不在库里，富途也不认识这个代码"));
        when(pool.returnToPool(5L)).thenReturn(true);

        Result r = service.syncNow(true, "MANUAL");

        assertThat(r.applied()).isTrue();
        assertThat(r.plan().changes()).extracting(Change::symbol).containsExactly("AAA", "NEWCO", "EEE");
        assertThat(r.errors()).singleElement().asString().contains("NEWCO");
        verify(pool).addHolding(eq(1L), anyString());
        verify(pool).returnToPool(5L);
        verify(poolService).notifyChanged();
        verify(marketData).backfillPending("MANUAL");
    }

    @Test
    void 快照里的现金管理工具与非股票不算持仓_池里误标HOLDING的现金工具会被移出() {
        when(pool.findAllForSync()).thenReturn(List.of(member(9, PoolRole.HOLDING, null)));
        when(instruments.findByIds(any())).thenReturn(List.of(row(9, "CASHX")));
        when(pool.addHolding(eq(1L), anyString())).thenReturn(true);
        when(pool.deleteHolding(9L)).thenReturn(true);

        Result r = service.syncFromSnapshot(List.of(
                new ValuedPosition(pos("AAA", "1001", "STK"), 1L, BigDecimal.ONE, PriceSource.BAR, false),
                new ValuedPosition(pos("CASHX", "1003", "STK"), 9L, BigDecimal.ONE, PriceSource.SNAPSHOT, true),
                new ValuedPosition(pos("AAA 261218C1", "1004", "OPT"), null, BigDecimal.ONE, PriceSource.BAR, false)));

        assertThat(r.plan().changes()).extracting(Change::action, Change::symbol)
                .containsExactly(tuple(Action.ADD, "AAA"), tuple(Action.REMOVE, "CASHX"));
        verify(pool).deleteHolding(9L);
        verify(marketData).backfillPending("SCHEDULE");
    }

    @Test
    void 池在此期间被别处改过时跳过并报出来_不触发回补() {
        when(pool.findAllForSync()).thenReturn(List.of(member(2, PoolRole.POOL, null)));
        when(instruments.findByIds(any())).thenReturn(List.of(row(2, "BBB")));
        when(pool.promoteToHolding(2L)).thenReturn(false);

        Result r = service.syncFromSnapshot(List.of(
                new ValuedPosition(pos("BBB", "1002", "STK"), 2L, BigDecimal.ONE, PriceSource.BAR, false)));

        assertThat(r.errors()).singleElement().asString().contains("已被别处改动");
        verify(poolService, never()).notifyChanged();
        verifyNoInteractions(marketData);
    }
}
