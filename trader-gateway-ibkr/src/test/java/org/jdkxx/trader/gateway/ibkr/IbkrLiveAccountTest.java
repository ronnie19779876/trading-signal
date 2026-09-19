package org.jdkxx.trader.gateway.ibkr;

import com.ib.client.Contract;
import com.ib.client.Decimal;
import com.ib.client.EClientSocket;
import org.jdkxx.trader.domain.AccountPnl;
import org.jdkxx.trader.domain.AccountSummary;
import org.jdkxx.trader.domain.Position;
import org.jdkxx.trader.domain.PositionPnl;
import org.jdkxx.trader.gateway.LiveAccountListener;
import org.jdkxx.trader.gateway.ibkr.mapper.IbkrAccounts;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

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
import static org.mockito.Mockito.inOrder;
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
        final List<Runnable> timers = new ArrayList<>();
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
        @Override public Runnable later(Duration delay, Runnable task) { timers.add(task); return () -> timers.remove(task); }
        @Override public Instant now() { return Instant.parse("2026-09-21T14:00:00Z"); }
    }

    /** 记下推送的监听器。 */
    static final class Recorder implements LiveAccountListener {
        final List<AccountSummary> summaries = new ArrayList<>();
        final List<AccountPnl> pnls = new ArrayList<>();
        final List<List<Position>> positions = new ArrayList<>();
        final List<PositionPnl> singles = new ArrayList<>();
        @Override public void onSummary(AccountSummary s) { summaries.add(s); }
        @Override public void onPnl(AccountPnl p) { pnls.add(p); }
        @Override public void onPositions(List<Position> p) { positions.add(p); }
        @Override public void onPositionPnl(PositionPnl p) { singles.add(p); }
    }

    private final FakeWire wire = new FakeWire();
    private final Recorder rec = new Recorder();
    private final IbkrLiveAccount live = new IbkrLiveAccount(ACCT, rec, wire);

    // subscribe() 依次占用 id：持仓 100、账户汇总 101、账户盈亏 102（再订一次是 103、104、105）
    private static final int POS = 100, SUM = 101, PNL = 102;

    private static IbkrAccounts.PositionRow pos(int conId, String symbol, double qty) {
        Contract c = new Contract();
        c.conid(conId);
        c.symbol(symbol);
        c.secType("STK");
        c.currency("USD");
        return new IbkrAccounts.PositionRow(ACCT, c, Decimal.get(qty), 100.0);
    }

    /** 守护：reqPnL 首条不完整（实测只含一只持仓），必须丢掉，否则当日盈亏开头会闪一个错的数。 */
    @Test
    void 账户盈亏首条丢弃() {
        live.subscribe();
        wire.handlers.get(PNL).item(new IbkrAccounts.PnlRow(26.67, 184.65, 0));
        wire.handlers.get(PNL).item(new IbkrAccounts.PnlRow(946.95, 22359.71, 0));

        assertThat(rec.pnls).hasSize(1);
        assertThat(rec.pnls.get(0).daily()).isEqualByComparingTo("946.95");
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

    /** 守护：1101（会话不变、订阅数据丢失）重订前先取消旧的，否则账户汇总撞上每客户端 2 个的上限（322）。 */
    @Test
    void 同一会话重订先取消旧订阅() {
        live.subscribe();
        live.subscribe();

        InOrder order = inOrder(wire.client);
        order.verify(wire.client).reqAccountSummary(eq(SUM), eq("All"), eq(IbkrAccounts.SUMMARY_TAGS));
        order.verify(wire.client).cancelAccountSummary(SUM);
        order.verify(wire.client).reqAccountSummary(eq(104), eq("All"), eq(IbkrAccounts.SUMMARY_TAGS));
    }

    /** 断线重连是新会话：旧订阅随会话失效，不能再对新会话发取消（那些 reqId 在新会话里不存在）。 */
    @Test
    void 新会话重订不发旧取消() {
        live.subscribe();
        wire.token = new Object();
        live.subscribe();

        verify(wire.client, never()).cancelAccountSummary(anyInt());
        verify(wire.client, never()).cancelPnL(anyInt());
        verify(wire.client, never()).cancelPositionsMulti(anyInt());
    }

    /** 账户汇总在 End 之后逐条推送、没有 End：攒到静默再发一次，而不是一条一发。 */
    @Test
    void 账户汇总逐条推送攒批后发一次() {
        live.subscribe();
        IbkrSubscriptions.Handler summary = wire.handlers.get(SUM);
        summary.item(new IbkrAccounts.SummaryRow(ACCT, "NetLiquidation", "508729.25", "USD"));
        summary.item(new IbkrAccounts.SummaryRow(ACCT, "TotalCashValue", "2801.18", "USD"));
        summary.item(new IbkrAccounts.SummaryRow(ACCT, "$LEDGER-StockMarketValue", "505548.58", "USD"));
        assertThat(wire.timers).hasSize(1);
        assertThat(rec.summaries).isEmpty();

        wire.timers.remove(0).run();
        assertThat(rec.summaries).hasSize(1);
        assertThat(rec.summaries.get(0).netLiquidation()).isEqualByComparingTo("508729.25");
        assertThat(rec.summaries.get(0).totalCash()).isEqualByComparingTo("2801.18");
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
        assertThat(wire.handlers).hasSize(3);
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
}
