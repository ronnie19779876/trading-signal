package org.jdkxx.trader.gateway.ibkr;

import com.ib.client.Contract;
import com.ib.client.Decimal;
import com.ib.client.EClientSocket;
import org.jdkxx.trader.domain.AccountPnl;
import org.jdkxx.trader.domain.AccountSummary;
import org.jdkxx.trader.domain.Position;
import org.jdkxx.trader.domain.PositionPnl;
import org.jdkxx.trader.domain.PositionPrice;
import org.jdkxx.trader.gateway.LiveAccountListener;
import org.jdkxx.trader.gateway.ibkr.mapper.IbkrAccounts;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 实时账户订阅的状态机。发给网关的请求 / 取消都作用到一个 EClientSocket 替身上核对，不连真网关。
 * 行为依据见 {@link IbkrLiveAccount} 类注释（2026-09-19 只读探针）。
 */
class IbkrLiveAccountTest {

    private static final String ACCT = "ACCT-A";

    /** 假连接：记下每个请求 / 取消，处理器按 id 存着，延时任务手动触发。 */
    static final class FakeWire implements IbkrLiveAccount.Wire {
        Object token = new Object();
        int next = 100;
        final EClientSocket client = mock(EClientSocket.class);
        final Map<Integer, IbkrSubscriptions.Handler> handlers = new HashMap<>();
        /** 延时任务：按延时记下，测试手动触发。 */
        final List<Map.Entry<Duration, Runnable>> timers = new ArrayList<>();
        boolean connected = true;

        @Override public Object sessionToken() { return token; }
        @Override public int nextId() { return next++; }
        @Override public boolean subscribe(String what, Consumer<EClientSocket> request) {
            if (!connected) return false;
            request.accept(client);
            return true;
        }
        @Override public void unsubscribe(String what, Consumer<EClientSocket> cancel) { cancel.accept(client); }
        @Override public void open(int id, IbkrSubscriptions.Handler handler) { handlers.put(id, handler); }
        @Override public void close(int id) { handlers.remove(id); }
        @Override public Runnable later(Duration delay, Runnable task) {
            Map.Entry<Duration, Runnable> e = Map.entry(delay, task);
            timers.add(e);
            return () -> timers.remove(e);
        }

        /** 取出并执行指定延时的那个任务。 */
        void fire(Duration delay) {
            Map.Entry<Duration, Runnable> e = timers.stream().filter(t -> t.getKey().equals(delay)).findFirst().orElseThrow();
            timers.remove(e);
            e.getValue().run();
        }

        long pending(Duration delay) {
            return timers.stream().filter(t -> t.getKey().equals(delay)).count();
        }
        @Override public Instant now() { return Instant.parse("2026-09-21T14:00:00Z"); }
    }

    /** 记下推送的监听器。 */
    static final class Recorder implements LiveAccountListener {
        final List<AccountSummary> summaries = new ArrayList<>();
        final List<AccountPnl> pnls = new ArrayList<>();
        final List<List<Position>> positions = new ArrayList<>();
        final List<PositionPnl> singles = new ArrayList<>();
        final List<PositionPrice> prices = new ArrayList<>();
        @Override public void onSummary(AccountSummary s) { summaries.add(s); }
        @Override public void onPnl(AccountPnl p) { pnls.add(p); }
        @Override public void onPositions(List<Position> p) { positions.add(p); }
        @Override public void onPositionPnl(PositionPnl p) { singles.add(p); }
        @Override public void onPositionPrice(PositionPrice p) { prices.add(p); }
    }

    private final FakeWire wire = new FakeWire();
    private final Recorder rec = new Recorder();
    private final IbkrLiveAccount live = new IbkrLiveAccount(ACCT, rec, wire);

    // subscribe() 依次占用 id：持仓 100、账户盈亏 101（账户汇总 3.0.9 起是常驻订阅，见 IbkrAccountSummaryFeedTest）
    private static final int POS = 100, PNL = 101;

    private static IbkrAccounts.PositionRow pos(int conId, String symbol, double qty) {
        Contract c = new Contract();
        c.conid(conId);
        c.symbol(symbol);
        c.secType("STK");
        c.currency("USD");
        return new IbkrAccounts.PositionRow(ACCT, c, Decimal.get(qty), 100.0);
    }

    // 两只持仓：positions.end() 后依次开 IBKR 逐只 102、IBKR 行情 103、SPY 逐只 104、SPY 行情 105
    private static final int SINGLE_IBKR = 102, SINGLE_SPY = 104;

    /** 持仓到齐（IBKR + SPY），逐只盈亏：当日之和 915.99、浮盈之和 22,328.76（09-19 生产账户的数拆成两只）。 */
    private void positionsAndSingles(boolean withSingles) {
        live.subscribe();
        wire.handlers.get(POS).item(pos(43645865, "IBKR", 11.1142));
        wire.handlers.get(POS).item(pos(756733, "SPY", 210));
        wire.handlers.get(POS).end();
        if (withSingles) {
            wire.handlers.get(SINGLE_IBKR).item(new IbkrAccounts.PnlSingleRow(Decimal.get(11.1142), 25.67, 183.65, Double.MAX_VALUE, 1007.95));
            wire.handlers.get(SINGLE_SPY).item(new IbkrAccounts.PnlSingleRow(Decimal.get(210), 890.32, 22145.11, Double.MAX_VALUE, 160225.80));
        }
    }

    /** 守护：刚连上时账户盈亏首条只含一只持仓（25.67），与逐只之和对不上，不采用；随后对得上的那条采用。 */
    @Test
    void 与逐只之和对不上的账户盈亏不采用() {
        positionsAndSingles(true);
        wire.handlers.get(PNL).item(new IbkrAccounts.PnlRow(25.67, 183.65, 0));
        assertThat(rec.pnls).isEmpty();

        wire.handlers.get(PNL).item(new IbkrAccounts.PnlRow(915.99, 22328.76, 0));
        assertThat(rec.pnls).extracting(x -> x.daily().toPlainString()).containsExactly("915.99");
    }

    /**
     * 守护（3.0.10）：当天有平仓时，账户当日含已平仓标的的当日已实现，逐只之和只覆盖在持的——扣掉已实现才对得上。
     *
     * <p>取自 2026-09-23 生产实况：卖掉 TSLA 之后当日盈亏整天判不通过、界面一直空着。
     * 这里用同一形态造数：逐只之和当日 915.99，账户当日 1280.50 = 915.99 + 已实现 364.51（TSLA 卖出对当日的贡献，
     * 由当天净值反推核出：(384.9752 − 378.90) × 60 = 364.51）。
     */
    @Test
    void 有平仓时扣掉已实现再核对() {
        positionsAndSingles(true);

        wire.handlers.get(PNL).item(new IbkrAccounts.PnlRow(1280.50, 22328.76, 364.51));

        assertThat(rec.pnls).extracting(x -> x.daily().toPlainString()).containsExactly("1280.5");
        assertThat(rec.pnls.get(0).realized().toPlainString()).isEqualTo("364.51");   // 显示仍是盈透原值
    }

    /** 守护：无平仓的日子 realized = 0，行为与 3.0.9 之前完全一致（零回归）。 */
    @Test
    void 无平仓时口径不变() {
        positionsAndSingles(true);

        wire.handlers.get(PNL).item(new IbkrAccounts.PnlRow(25.67, 183.65, 0));       // 首条只含一只：仍不采用
        assertThat(rec.pnls).isEmpty();

        wire.handlers.get(PNL).item(new IbkrAccounts.PnlRow(915.99, 22328.76, 0));
        assertThat(rec.pnls).hasSize(1);
    }

    /**
     * 守护：realized 未设（Double.MAX_VALUE → null）时不作调整，于是当日对不上就判不通过。
     * 这是安全方向——宁可当日盈亏空着，也不放行一条对不上的推送；日志里会写"已实现=未设"便于事后分辨。
     */
    @Test
    void 已实现未设时不作调整_对不上就不采用() {
        positionsAndSingles(true);

        // 当日比逐只之和多 364.51，但 realized 未设：无从扣除，判不通过
        wire.handlers.get(PNL).item(new IbkrAccounts.PnlRow(1280.50, 22328.76, Double.MAX_VALUE));
        assertThat(rec.pnls).isEmpty();

        // 不需要调整就对得上的照常采用
        wire.handlers.get(PNL).item(new IbkrAccounts.PnlRow(915.99, 22328.76, Double.MAX_VALUE));
        assertThat(rec.pnls).hasSize(1);
    }

    /** 守护：浮盈那一半不因为扣已实现而放松——只有当日含已实现，浮盈两边口径本来就一致。 */
    @Test
    void 浮盈对不上仍然不采用() {
        positionsAndSingles(true);

        wire.handlers.get(PNL).item(new IbkrAccounts.PnlRow(1280.50, 99999.99, 364.51));

        assertThat(rec.pnls).isEmpty();
    }

    /**
     * 守护：逐只盈亏稳定运行后再订，账户盈亏只推一条而且是对的、价格静止时不再有第二条（09-19 实测）——
     * 这一条必须采用。3.0.3 一律丢首条，结果当日盈亏一直"等待盈透推送"。
     */
    @Test
    void 唯一一条正确推送直接采用() {
        positionsAndSingles(true);
        wire.handlers.get(PNL).item(new IbkrAccounts.PnlRow(915.99, 22328.76, 0));
        assertThat(rec.pnls).hasSize(1);
    }

    /** 账户盈亏先到、逐只后到：先挂起，逐只到齐后核对通过再发；采用过一次后后续推送直接放行。 */
    @Test
    void 账户盈亏先到时等逐只到齐再核() {
        positionsAndSingles(false);
        wire.handlers.get(PNL).item(new IbkrAccounts.PnlRow(915.99, 22328.76, 0));
        assertThat(rec.pnls).isEmpty();

        wire.handlers.get(SINGLE_IBKR).item(new IbkrAccounts.PnlSingleRow(Decimal.get(11.1142), 25.67, 183.65, Double.MAX_VALUE, 1007.95));
        assertThat(rec.pnls).isEmpty();   // 还缺 SPY
        wire.handlers.get(SINGLE_SPY).item(new IbkrAccounts.PnlSingleRow(Decimal.get(210), 890.32, 22145.11, Double.MAX_VALUE, 160225.80));
        assertThat(rec.pnls).hasSize(1);

        wire.handlers.get(PNL).item(new IbkrAccounts.PnlRow(920.00, 22332.77, 0));   // 盘中变了：已通过核对，直接放行
        assertThat(rec.pnls).hasSize(2);
    }

    /** 守护：逐只盈亏跟着持仓走——End 后按持仓逐只订，清仓（数量 0）的退订并从列表里拿掉。 */
    @Test
    void 逐只盈亏跟着持仓订退() {
        live.subscribe();
        IbkrSubscriptions.Handler positions = wire.handlers.get(POS);
        positions.item(pos(265598, "AAPL", 10));
        positions.item(pos(72063691, "BRK B", 80));
        verify(wire.client, never()).reqPnLSingle(anyInt(), eq(ACCT), eq(""), anyInt());   // End 之前不订

        positions.end();
        verify(wire.client).reqPnLSingle(anyInt(), eq(ACCT), eq(""), eq(265598));
        verify(wire.client).reqPnLSingle(anyInt(), eq(ACCT), eq(""), eq(72063691));
        assertThat(rec.positions.get(0)).extracting(Position::symbol).containsExactly("AAPL", "BRK B");
        assertThat(live.singleCount()).isEqualTo(2);

        positions.item(pos(265598, "AAPL", 0));   // 盘中清仓
        verify(wire.client).cancelPnLSingle(anyInt());
        assertThat(rec.positions.get(1)).extracting(Position::symbol).containsExactly("BRK B");
        assertThat(live.singleCount()).isEqualTo(1);
    }

    /** 未连接时订阅失败：不留半截状态，连上后可以整组再订。 */
    @Test
    void 未连接时不留半截订阅() {
        wire.connected = false;
        assertThat(live.subscribe()).isFalse();
        assertThat(live.subscribed()).isFalse();
        assertThat(wire.handlers).isEmpty();

        wire.connected = true;
        assertThat(live.subscribe()).isTrue();
        assertThat(wire.handlers).hasSize(2);       // 持仓 + 账户盈亏；账户汇总已移到常驻订阅
    }

    @Test
    void 逐只盈亏的未设值映射成空且账户号不进字符串() {
        PositionPnl p = IbkrAccounts.positionPnl("756733", new IbkrAccounts.PnlSingleRow(Decimal.get(210), 449.15, 19828.81,
                Double.MAX_VALUE, 160198.49), Instant.EPOCH);
        assertThat(p.realized()).isNull();
        assertThat(p.marketValue()).isEqualByComparingTo("160198.49");
        live.subscribe();
        assertThat(live.toString()).doesNotContain(ACCT);
    }

    private static final Duration WATCHDOG = Duration.ofSeconds(IbkrLiveAccount.PNL_WATCHDOG_SECONDS);

    /** 行情跟着持仓走：End 后按 conId 走 SMART 订行情，清仓的退订。 */
    @Test
    void 持仓行情跟着持仓订退() {
        live.subscribe();
        IbkrSubscriptions.Handler positions = wire.handlers.get(POS);
        positions.item(pos(208813720, "GOOG", 62));
        positions.end();

        org.mockito.ArgumentCaptor<Contract> c = org.mockito.ArgumentCaptor.forClass(Contract.class);
        verify(wire.client).reqMktData(anyInt(), c.capture(), eq(""), eq(false), eq(false), org.mockito.ArgumentMatchers.isNull());
        assertThat(c.getValue().conid()).isEqualTo(208813720);
        assertThat(c.getValue().exchange()).isEqualTo("SMART");
        assertThat(live.quoteCount()).isEqualTo(1);

        positions.item(pos(208813720, "GOOG", 0));
        verify(wire.client).cancelMktData(anyInt());
        assertThat(live.quoteCount()).isZero();
    }

    /** 只收最新价（LAST）与前收（CLOSE），收盘后的 -1 买卖价与其他 tick 忽略；两者合在一起发；延迟行情要标出来。 */
    @Test
    void 现价只取最新价与前收() {
        live.subscribe();
        wire.handlers.get(POS).item(pos(208813720, "GOOG", 62));
        wire.handlers.get(POS).end();
        IbkrSubscriptions.Handler quote = wire.handlers.entrySet().stream()
                .filter(e -> e.getKey() > PNL).reduce((a, b) -> b).orElseThrow().getValue();   // 最后开的是行情

        quote.item(new IbkrAccounts.TickRow(1, -1.0));      // 收盘后买价 -1：忽略
        quote.item(new IbkrAccounts.TickRow(14, 351.35));   // 开盘价：不关心
        quote.item(new IbkrAccounts.TickRow(9, 343.68));    // 前收
        quote.item(new IbkrAccounts.TickRow(4, 346.08));    // 最新价
        assertThat(rec.prices).hasSize(2);
        assertThat(rec.prices.get(0).last()).isNull();
        assertThat(rec.prices.get(0).priorClose()).isEqualByComparingTo("343.68");
        assertThat(rec.prices.get(1).last()).isEqualByComparingTo("346.08");
        assertThat(rec.prices.get(1).priorClose()).isEqualByComparingTo("343.68");
        assertThat(rec.prices.get(1).delayed()).isFalse();

        quote.item(new IbkrAccounts.MarketDataTypeRow(3));   // 降级成延迟行情
        quote.item(new IbkrAccounts.TickRow(68, 346.10));
        assertThat(rec.prices.get(2).delayed()).isTrue();
    }

    /** 守护：一直没有通过核对的推送（只来了错的那条、第二条不来）：10 秒后重订；通过核对后不再重订。 */
    @Test
    void 账户盈亏没有有效值时重订() {
        positionsAndSingles(true);
        wire.handlers.get(PNL).item(new IbkrAccounts.PnlRow(25.67, 183.65, 0));   // 错的，不采用
        assertThat(rec.pnls).isEmpty();

        wire.fire(WATCHDOG);
        verify(wire.client).cancelPnL(PNL);
        int second = wire.handlers.keySet().stream().max(Integer::compare).orElseThrow();
        wire.handlers.get(second).item(new IbkrAccounts.PnlRow(991.93, 23308.54, 0));   // 重订后的首条也可能是错的
        wire.handlers.get(second).item(new IbkrAccounts.PnlRow(915.99, 22328.76, 0));
        assertThat(rec.pnls).extracting(x -> x.daily().toPlainString()).containsExactly("915.99");

        wire.fire(WATCHDOG);   // 已有有效值：不再重订
        verify(wire.client, org.mockito.Mockito.times(1)).cancelPnL(anyInt());
    }

    /** 重订最多 3 次，之后不再打扰网关。 */
    @Test
    void 账户盈亏重订有上限() {
        live.subscribe();
        for (int i = 0; i < IbkrLiveAccount.PNL_MAX_RETRIES; i++) {
            wire.fire(WATCHDOG);
        }
        assertThat(wire.pending(WATCHDOG)).isZero();
        verify(wire.client, org.mockito.Mockito.times(IbkrLiveAccount.PNL_MAX_RETRIES)).cancelPnL(anyInt());
    }

    // ---- 账户汇总撞上每客户端 2 个上限（322）：2026-09-21 生产实测，网关每日重启重连后整个上午取不到资金 ----

}
