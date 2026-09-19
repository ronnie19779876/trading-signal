package org.jdkxx.trader.gateway.ibkr;

import com.ib.client.EClientSocket;
import org.jdkxx.trader.domain.Position;
import org.jdkxx.trader.gateway.GatewayException;
import org.jdkxx.trader.gateway.LiveAccountListener;
import org.jdkxx.trader.gateway.RequestRejectedException;
import org.jdkxx.trader.gateway.ibkr.mapper.IbkrAccounts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 一个账户的实时订阅（3.0.2 起）：常驻账户汇总、账户盈亏、持仓，加上每只持仓一个逐只盈亏。
 *
 * <p><b>所有方法都在 dispatch 线程上执行</b>（{@link IbkrGateway} 负责转过来，订阅回调也由 {@link IbkrSubscriptions}
 * 转到 dispatch），所以状态不加锁。
 *
 * <p>实测依据（2026-09-19 只读探针）：
 * <ul>
 *   <li>账户汇总首批约 0.3 秒回齐，之后<b>逐条</b>推送、不再有 End，约 3 分钟一批 → 逐条攒起来、静默 {@value #SUMMARY_DEBOUNCE_MS} ms 后发一次；</li>
 *   <li>reqPnL <b>首条不完整</b>（只含一只持仓），1 秒后第二条才完整 → 丢掉首条；</li>
 *   <li>持仓 End 之后成交会再推变动行，数量为 0 表示清仓 → 维护完整列表，每次变动发一次全量，并同步逐只盈亏的订阅。</li>
 * </ul>
 * 1101（连接恢复、订阅数据丢失）时会话不变，旧订阅还挂在网关上：先取消再重订，否则账户汇总会撞上每客户端 2 个的上限（322）。
 */
final class IbkrLiveAccount {

    private static final Logger log = LoggerFactory.getLogger(IbkrLiveAccount.class);
    static final long SUMMARY_DEBOUNCE_MS = 300;

    /** 与连接之间的接缝：生产用 {@link IbkrConnection}，单测用替身。 */
    interface Wire {
        Object sessionToken();

        int nextId();

        boolean subscribe(String what, Consumer<EClientSocket> request);

        void unsubscribe(String what, Consumer<EClientSocket> cancel);

        void open(int id, IbkrSubscriptions.Handler handler);

        void close(int id);

        /** 延时任务：到点在 dispatch 线程上执行。 */
        Runnable later(Duration delay, Runnable task);

        Instant now();
    }

    private record Sub(int id, String what, Consumer<EClientSocket> cancel) {
    }

    final String accountId;
    private LiveAccountListener listener;
    private final Wire wire;

    /** 订上时所在的会话（{@link Wire#sessionToken()}）；null = 当前没有订阅 */
    private Object bound;
    private Sub positionsSub;
    private Sub summarySub;
    private Sub pnlSub;
    private final Map<String, Sub> singles = new LinkedHashMap<>();

    private final Map<String, IbkrAccounts.PositionRow> positions = new LinkedHashMap<>();
    private boolean positionsReady;
    private final Map<String, IbkrAccounts.SummaryRow> summaryRows = new LinkedHashMap<>();
    private Runnable summaryFlush;
    private boolean pnlFirstSeen;

    IbkrLiveAccount(String accountId, LiveAccountListener listener, Wire wire) {
        this.accountId = accountId;
        this.listener = listener;
        this.wire = wire;
    }

    void listener(LiveAccountListener listener) {
        this.listener = listener;
    }

    boolean subscribed() {
        return bound != null;
    }

    /** 订阅（或重订）。未连接时什么也不做，返回 false，等连上后再调。 */
    boolean subscribe() {
        Object current = wire.sessionToken();
        if (bound != null && bound == current) {
            cancelAll();        // 同一会话（1101）：旧订阅还活着，先退
        } else {
            forgetAll();        // 旧会话已结束：订阅随之失效，只丢掉记录
        }
        bound = current;
        positionsReady = false;
        pnlFirstSeen = false;

        positionsSub = open("实时持仓", positionsHandler(),
                id -> c -> c.reqPositionsMulti(id, accountId, ""), id -> c -> c.cancelPositionsMulti(id));
        summarySub = open("实时账户汇总", summaryHandler(),
                id -> c -> c.reqAccountSummary(id, "All", IbkrAccounts.SUMMARY_TAGS), id -> c -> c.cancelAccountSummary(id));
        pnlSub = open("实时账户盈亏", pnlHandler(),
                id -> c -> c.reqPnL(id, accountId, ""), id -> c -> c.cancelPnL(id));
        if (positionsSub == null || summarySub == null || pnlSub == null) {
            cancelAll();        // 发一半就断了：退掉已发的，等下一次连上整组重订
            bound = null;
            return false;
        }
        return true;
    }

    /** 退订全部。 */
    void stop() {
        if (bound != null && bound == wire.sessionToken()) {
            cancelAll();
        } else {
            forgetAll();
        }
        bound = null;
    }

    int singleCount() {
        return singles.size();
    }

    private Sub open(String what, IbkrSubscriptions.Handler handler,
                     java.util.function.IntFunction<Consumer<EClientSocket>> request,
                     java.util.function.IntFunction<Consumer<EClientSocket>> cancel) {
        int id = wire.nextId();
        wire.open(id, handler);
        if (!wire.subscribe(what, request.apply(id))) {
            wire.close(id);
            return null;
        }
        return new Sub(id, what, cancel.apply(id));
    }

    private void cancel(Sub sub) {
        if (sub != null) {
            wire.close(sub.id());
            wire.unsubscribe(sub.what(), sub.cancel());
        }
    }

    private void cancelAll() {
        cancel(positionsSub);
        cancel(summarySub);
        cancel(pnlSub);
        singles.values().forEach(this::cancel);
        clearState();
    }

    private void forgetAll() {
        for (Sub s : allSubs()) {
            wire.close(s.id());
        }
        clearState();
    }

    private List<Sub> allSubs() {
        List<Sub> all = new ArrayList<>(singles.values());
        for (Sub s : new Sub[] {positionsSub, summarySub, pnlSub}) {
            if (s != null) {
                all.add(s);
            }
        }
        return all;
    }

    private void clearState() {
        positionsSub = summarySub = pnlSub = null;
        singles.clear();
        positions.clear();
        summaryRows.clear();
        if (summaryFlush != null) {
            summaryFlush.run();   // later() 返回的是取消动作
            summaryFlush = null;
        }
        positionsReady = false;
    }

    // ------------------------------------------------------------------ 持仓

    private IbkrSubscriptions.Handler positionsHandler() {
        return new IbkrSubscriptions.Handler() {
            @Override
            public void item(Object item) {
                IbkrAccounts.PositionRow r = (IbkrAccounts.PositionRow) item;
                if (r.contract() == null || !accountId.equals(r.account())) {
                    return;
                }
                String conId = Integer.toString(r.contract().conid());
                if (r.position() == null || !r.position().isValid()) {
                    log.warn("实时持仓收到无效数量（{}），忽略这一条", r.contract().symbol());
                    return;
                }
                if (r.position().value().signum() == 0) {
                    positions.remove(conId);   // 清仓
                } else {
                    positions.put(conId, r);
                }
                if (positionsReady) {
                    emitPositions();
                }
            }

            @Override
            public void end() {
                positionsReady = true;
                emitPositions();
            }

            @Override
            public void error(RequestRejectedException e) {
                listener.onLiveError("实时持仓", e);
            }
        };
    }

    private void emitPositions() {
        try {
            List<Position> list = IbkrAccounts.positions(accountId, new ArrayList<>(positions.values()));
            listener.onPositions(list);
        } catch (RuntimeException e) {
            log.warn("实时持仓映射失败：{}", e.toString());
        }
        syncSingles();
    }

    /** 逐只盈亏的订阅跟着持仓走：新出现的订、清仓的退。 */
    private void syncSingles() {
        for (String conId : new ArrayList<>(singles.keySet())) {
            if (!positions.containsKey(conId)) {
                cancel(singles.remove(conId));
            }
        }
        for (String conId : positions.keySet()) {
            if (singles.containsKey(conId)) {
                continue;
            }
            int con = Integer.parseInt(conId);
            Sub s = open("逐只盈亏 " + conId, singleHandler(conId),
                    id -> c -> c.reqPnLSingle(id, accountId, "", con), id -> c -> c.cancelPnLSingle(id));
            if (s != null) {
                singles.put(conId, s);
            }
        }
    }

    private IbkrSubscriptions.Handler singleHandler(String conId) {
        return new IbkrSubscriptions.Handler() {
            @Override
            public void item(Object item) {
                listener.onPositionPnl(IbkrAccounts.positionPnl(conId, (IbkrAccounts.PnlSingleRow) item, wire.now()));
            }

            @Override
            public void error(RequestRejectedException e) {
                listener.onLiveError("逐只盈亏", e);
            }
        };
    }

    // ------------------------------------------------------------------ 账户汇总

    private IbkrSubscriptions.Handler summaryHandler() {
        return new IbkrSubscriptions.Handler() {
            @Override
            public void item(Object item) {
                IbkrAccounts.SummaryRow r = (IbkrAccounts.SummaryRow) item;
                if (r.tag() == null) {
                    return;
                }
                summaryRows.put(r.account() + "|" + r.tag(), r);   // "All" 组可能含多个账户，按账户 + 标签存
                if (summaryFlush == null) {
                    summaryFlush = wire.later(Duration.ofMillis(SUMMARY_DEBOUNCE_MS), IbkrLiveAccount.this::flushSummary);
                }
            }

            @Override
            public void end() {
                if (summaryFlush != null) {
                    summaryFlush.run();
                    summaryFlush = null;
                }
                flushSummary();
            }

            @Override
            public void error(RequestRejectedException e) {
                listener.onLiveError("实时账户汇总", e);
            }
        };
    }

    private void flushSummary() {
        summaryFlush = null;
        if (summaryRows.isEmpty()) {
            return;
        }
        try {
            listener.onSummary(IbkrAccounts.summary(accountId, new ArrayList<>(summaryRows.values()), wire.now()));
        } catch (GatewayException e) {
            listener.onLiveError("实时账户汇总", e);
        }
    }

    // ------------------------------------------------------------------ 账户盈亏

    private IbkrSubscriptions.Handler pnlHandler() {
        return new IbkrSubscriptions.Handler() {
            @Override
            public void item(Object item) {
                if (!pnlFirstSeen) {
                    pnlFirstSeen = true;   // 首条不完整（实测只含一只持仓），丢掉
                    return;
                }
                listener.onPnl(IbkrAccounts.pnl((IbkrAccounts.PnlRow) item, wire.now()));
            }

            @Override
            public void error(RequestRejectedException e) {
                listener.onLiveError("实时账户盈亏", e);
            }
        };
    }

    @Override
    public String toString() {
        return "IbkrLiveAccount[**** subscribed=" + subscribed() + " positions=" + positions.size() + "]";
    }
}
