package org.jdkxx.trader.core.account;

import org.jdkxx.trader.core.marketdata.jobs.JobContext;
import org.jdkxx.trader.domain.AccountKind;
import org.jdkxx.trader.domain.AccountRef;
import org.jdkxx.trader.domain.AccountSummary;
import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.PoolRole;
import org.jdkxx.trader.domain.Position;
import org.jdkxx.trader.domain.SecurityType;
import org.jdkxx.trader.domain.ValuationSnapshot;
import org.jdkxx.trader.gateway.AccountGateway;
import org.jdkxx.trader.gateway.BrokerGateway;
import org.jdkxx.trader.gateway.MarketDataGateway;
import org.jdkxx.trader.storage.account.AccountSnapshotRepository;
import org.jdkxx.trader.storage.account.AccountSnapshotRow;
import org.jdkxx.trader.storage.account.PositionSnapshotRow;
import org.jdkxx.trader.storage.marketdata.DailyBarRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.jdkxx.trader.storage.marketdata.PoolRepository;
import org.jdkxx.trader.storage.marketdata.PoolRow;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 账户、代码、持仓全部虚构。 */
class AccountSnapshotServiceTest {

    private static final String ACCT = "ACCT-A";
    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final LocalDate MON = LocalDate.of(2026, 9, 14);
    /** 美东 2026-09-14 18:00。 */
    private static final Clock AT_18_ET = Clock.fixed(Instant.parse("2026-09-14T22:00:00Z"), ZoneOffset.UTC);
    private static final String SECRET = "test-only-secret-0123456789";   // secrets-ok 测试常量，不是真密钥

    private final BrokerGateway broker = mock(BrokerGateway.class);
    private final AccountGateway accounts = mock(AccountGateway.class);
    private final MarketDataGateway market = mock(MarketDataGateway.class);
    private final InstrumentRepository instruments = mock(InstrumentRepository.class);
    private final DailyBarRepository bars = mock(DailyBarRepository.class);
    private final PoolRepository pool = mock(PoolRepository.class);
    private final TradingDayRepository days = mock(TradingDayRepository.class);
    private final AccountSnapshotRepository snapshots = mock(AccountSnapshotRepository.class);
    private final JobContext ctx = mock(JobContext.class);

    private AccountSnapshotService service(String secret, Clock clock, String configuredAccount) {
        AccountProperties props = new AccountProperties(true, "0 0 18 * * MON-FRI", secret, List.of("CASHX"),
                new BigDecimal("0.002"), BigDecimal.ONE);
        return service(secret, clock, configuredAccount, null);
    }

    private AccountSnapshotService service(String secret, Clock clock, String configuredAccount, HoldingSyncService holdingSync) {
        AccountProperties props = new AccountProperties(true, "0 0 18 * * MON-FRI", secret, List.of("CASHX"),
                new BigDecimal("0.002"), BigDecimal.ONE);
        return new AccountSnapshotService(props, configuredAccount, broker, accounts, market, instruments, bars, pool, days,
                snapshots, holdingSync, clock, ET);
    }

    private static Position pos(String symbol, String conId, String qty, String cost) {
        return new Position(Broker.IBKR, ACCT, conId, symbol, symbol, "STK", "NYSE", null, "USD", new BigDecimal(qty),
                cost == null ? null : new BigDecimal(cost));
    }

    private static InstrumentRow row(long id, String symbol) {
        return new InstrumentRow(id, Market.US, symbol, symbol, null, SecurityType.STOCK, 1, null, false, null, 1L, "RESOLVED");
    }

    private static ValuationSnapshot snap(String symbol, Instant asOf, String price) {
        return new ValuationSnapshot(Instrument.us(symbol), asOf, false, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, new BigDecimal(price));
    }

    private static AccountSummary summary(String net, String cash, String stock, String div) {
        return new AccountSummary(Broker.IBKR, ACCT, Instant.parse("2026-09-14T22:00:00Z"), "USD", new BigDecimal(net),
                new BigDecimal(cash), new BigDecimal(stock), null, null, null, null, null, null, new BigDecimal(div),
                Map.of("AccountType", "INDIVIDUAL"));
    }

    private static AccountRef ref(String id) {
        return new AccountRef(Broker.IBKR, id, AccountKind.LIVE, Set.of(Market.US));
    }

    @BeforeEach
    void common() {
        when(broker.accounts()).thenReturn(completedFuture(List.of(ref(ACCT))));
        when(days.isTradingDay(eq(Market.US), any())).thenAnswer(inv -> MON.equals(inv.getArgument(1)));
        when(ctx.id()).thenReturn(7L);
        when(instruments.findIdByIbkrConId(anyLong())).thenReturn(Optional.empty());
    }

    @SuppressWarnings("unchecked")
    @Test
    void 按conId与代码映射_K线估值_库里没有的用当日快照价_对账通过并落库() throws Exception {
        when(accounts.positions(ACCT)).thenReturn(completedFuture(List.of(
                pos("XYZ B", "1001", "10", "90"),
                pos("ABC", "1002", "5", "25"),
                pos("CASHX", "1003", "100", "100"),
                pos("OLD", "1004", "0", "1"))));
        // 股票市值 = 10×100 + 5×20 + 100×100.5 = 11150；净值 = 现金 50 + 11150 + 应计股息 0
        when(accounts.accountSummary(ACCT)).thenReturn(completedFuture(summary("11200", "50", "11150", "0")));
        when(instruments.findIdByIbkrConId(1002L)).thenReturn(Optional.of(12L));
        when(instruments.find(Instrument.us("XYZ.B"))).thenReturn(Optional.of(row(11, "XYZ.B")));
        when(instruments.find(Instrument.us("CASHX"))).thenReturn(Optional.empty());
        when(instruments.bindIbkrConId(11L, 1001L)).thenReturn(true);
        when(bars.closesOn(eq(MON), any())).thenReturn(Map.of(11L, new BigDecimal("100"), 12L, new BigDecimal("20")));
        when(market.snapshots(anyList())).thenReturn(completedFuture(List.of(
                snap("CASHX", Instant.parse("2026-09-14T20:00:00Z"), "100.5"))));
        when(pool.findAll()).thenReturn(List.of(new PoolRow(11, PoolRole.HOLDING, null, Instant.EPOCH),
                new PoolRow(12, PoolRole.HOLDING, null, Instant.EPOCH)));
        when(instruments.findByIds(any())).thenReturn(List.of(row(11, "XYZ.B"), row(12, "ABC")));

        String result = service(SECRET, AT_18_ET, null).run(ctx, false);

        ArgumentCaptor<AccountSnapshotRow> header = ArgumentCaptor.forClass(AccountSnapshotRow.class);
        ArgumentCaptor<List<AccountSnapshotRepository.ReconCheck>> checks = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<PositionSnapshotRow>> rows = ArgumentCaptor.forClass(List.class);
        verify(snapshots).save(header.capture(), eq(Map.of("AccountType", "INDIVIDUAL")), checks.capture(), rows.capture());

        assertThat(result).contains("对账 OK").contains("K线 2 / 快照 1 / 缺价 0").doesNotContain(ACCT);
        AccountSnapshotRow h = header.getValue();
        assertThat(h.asOfDate()).isEqualTo(MON);
        assertThat(h.accountKey()).hasSize(32).doesNotContain(ACCT);
        assertThat(h.accountMask()).isEqualTo("AC*****");
        assertThat(h.positions()).as("数量为 0 的当天清仓条目不进快照").isEqualTo(3);
        assertThat(h.reconStatus()).isEqualTo("OK");
        assertThat(h.positionValue()).isEqualByComparingTo("11150");
        assertThat(h.jobRunId()).isEqualTo(7L);
        assertThat(rows.getValue())
                .extracting(PositionSnapshotRow::symbol, PositionSnapshotRow::instrumentId, PositionSnapshotRow::priceSource,
                        PositionSnapshotRow::cashEquivalent)
                .containsExactly(tuple("XYZ B", 11L, "BAR", false), tuple("ABC", 12L, "BAR", false),
                        tuple("CASHX", null, "SNAPSHOT", true));
        assertThat(checks.getValue()).extracting(AccountSnapshotRepository.ReconCheck::status).containsOnly("OK");
        verify(instruments).bindIbkrConId(11L, 1001L);
        verify(ctx, never()).partial(anyString());
    }

    @SuppressWarnings("unchecked")
    @Test
    void 下一个交易日开始后快照价已不是收盘价_记缺价_对账WARN() throws Exception {
        Clock tuesday10 = Clock.fixed(Instant.parse("2026-09-15T14:00:00Z"), ZoneOffset.UTC);   // 美东周二 10:00，已开盘
        when(days.between(eq(Market.US), any(), any())).thenReturn(List.of(MON, MON.plusDays(1)));
        when(accounts.positions(ACCT)).thenReturn(completedFuture(List.of(pos("CASHX", "1003", "100", "100"))));
        when(accounts.accountSummary(ACCT)).thenReturn(completedFuture(summary("10100", "50", "10050", "0")));
        when(instruments.find(any())).thenReturn(Optional.empty());
        when(bars.closesOn(eq(MON), any())).thenReturn(Map.of());
        // 周二盘中的快照价：不能冒充周一收盘
        when(market.snapshots(anyList())).thenReturn(completedFuture(List.of(
                snap("CASHX", Instant.parse("2026-09-15T14:00:00Z"), "101.2"))));

        service(SECRET, tuesday10, null).run(ctx, true);   // force：按最近已收盘交易日（周一）口径

        ArgumentCaptor<AccountSnapshotRow> header = ArgumentCaptor.forClass(AccountSnapshotRow.class);
        ArgumentCaptor<List<PositionSnapshotRow>> rows = ArgumentCaptor.forClass(List.class);
        verify(snapshots).save(header.capture(), any(), any(), rows.capture());
        assertThat(rows.getValue()).extracting(PositionSnapshotRow::priceSource).containsExactly("NONE");
        assertThat(header.getValue().reconStatus()).isEqualTo("WARN");
        verify(ctx).partial(contains("WARN"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void 快照时间戳跟着夜盘走也照样认收盘价() throws Exception {
        // 实测：美东周日 20:52 取 SPY 快照，时间戳是周日（夜盘），价格仍是周五收盘，与 K 线收盘一致
        Clock sunday2052 = Clock.fixed(Instant.parse("2026-09-14T00:52:00Z"), ZoneOffset.UTC);
        LocalDate fri = LocalDate.of(2026, 9, 11);
        when(days.between(eq(Market.US), any(), any())).thenReturn(List.of(LocalDate.of(2026, 9, 10), fri, MON));
        when(accounts.positions(ACCT)).thenReturn(completedFuture(List.of(pos("CASHX", "1003", "100", "100"))));
        when(accounts.accountSummary(ACCT)).thenReturn(completedFuture(summary("10100", "50", "10050", "0")));
        when(instruments.find(any())).thenReturn(Optional.empty());
        when(bars.closesOn(eq(fri), any())).thenReturn(Map.of());
        when(market.snapshots(anyList())).thenReturn(completedFuture(List.of(
                snap("CASHX", Instant.parse("2026-09-14T00:52:00Z"), "100.5"))));

        service(SECRET, sunday2052, null).run(ctx, true);

        ArgumentCaptor<AccountSnapshotRow> header = ArgumentCaptor.forClass(AccountSnapshotRow.class);
        ArgumentCaptor<List<PositionSnapshotRow>> rows = ArgumentCaptor.forClass(List.class);
        verify(snapshots).save(header.capture(), any(), any(), rows.capture());
        assertThat(header.getValue().asOfDate()).isEqualTo(fri);
        assertThat(rows.getValue()).extracting(PositionSnapshotRow::priceSource).containsExactly("SNAPSHOT");
        assertThat(header.getValue().reconStatus()).isEqualTo("OK");
    }

    @Test
    void 快照里先按持仓同步HOLDING再对账_对账读到的是同步后的池() throws Exception {
        HoldingSyncService sync = mock(HoldingSyncService.class);
        when(sync.syncFromSnapshot(any())).thenReturn(new HoldingSyncService.Result(true,
                new HoldingSyncService.Plan(List.of(), List.of(), null), List.of(), "无需变动"));
        when(days.between(eq(Market.US), any(), any())).thenReturn(List.of(LocalDate.of(2026, 9, 11), MON));
        when(accounts.positions(ACCT)).thenReturn(completedFuture(List.of()));
        when(accounts.accountSummary(ACCT)).thenReturn(completedFuture(summary("50", "50", "0", "0")));

        String result = service(SECRET, AT_18_ET, null, sync).run(ctx, false);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(sync, pool, snapshots);
        order.verify(sync).syncFromSnapshot(any());
        order.verify(pool).findAll();
        order.verify(snapshots).save(any(), any(), any(), any());
        assertThat(result).contains("持仓同步：无需变动");
    }

    @Test
    void 窗口外拒绝_不去券商取数() {
        Clock at11 = Clock.fixed(Instant.parse("2026-09-14T15:00:00Z"), ZoneOffset.UTC);

        assertThatThrownBy(() -> service(SECRET, at11, null).run(ctx, false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("快照窗口");
        verifyNoInteractions(accounts, snapshots);
    }

    @Test
    void 密钥缺失时拒绝_不去券商取数() {
        assertThatThrownBy(() -> service(null, AT_18_ET, null).run(ctx, false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("key-secret");
        verifyNoInteractions(accounts, snapshots);
    }

    @Test
    void 多个受管账户又没指定时拒绝_消息不带账户号() {
        when(broker.accounts()).thenReturn(completedFuture(List.of(ref(ACCT), ref("ACCT-B"))));

        assertThatThrownBy(() -> service(SECRET, AT_18_ET, null).run(ctx, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2 个受管账户")
                .hasMessageNotContaining(ACCT).hasMessageNotContaining("ACCT-B");
        verifyNoInteractions(accounts, snapshots);
    }

    @Test
    void 指定的账户不在受管列表时拒绝_消息不带账户号() {
        assertThatThrownBy(() -> service(SECRET, AT_18_ET, "ACCT-Z").run(ctx, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不在盈透受管账户列表")
                .hasMessageNotContaining("ACCT-Z");
        verifyNoInteractions(accounts, snapshots);
    }

    @Test
    void force按最近一个已收盘交易日口径拍() throws Exception {
        Clock sundayNight = Clock.fixed(Instant.parse("2026-09-13T23:00:00Z"), ZoneOffset.UTC);
        when(days.between(eq(Market.US), any(), any())).thenReturn(List.of(LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 11)));
        when(accounts.positions(ACCT)).thenReturn(completedFuture(List.of()));
        when(accounts.accountSummary(ACCT)).thenReturn(completedFuture(summary("50", "50", "0", "0")));

        service(SECRET, sundayNight, null).run(ctx, true);

        ArgumentCaptor<AccountSnapshotRow> header = ArgumentCaptor.forClass(AccountSnapshotRow.class);
        verify(snapshots).save(header.capture(), any(), any(), any());
        assertThat(header.getValue().asOfDate()).isEqualTo(LocalDate.of(2026, 9, 11));
    }
}
