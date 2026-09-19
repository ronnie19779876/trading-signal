package org.jdkxx.trader.gateway.ibkr;

import com.ib.client.Contract;
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
 * 一个账户的实时订阅（3.0.2 起）：常驻账户汇总、账户盈亏、持仓，加上每只持仓一个逐只盈亏与一条行情（取最新价）。
 *
 * <p><b>所有方法都在 dispatch 线程上执行</b>（{@link IbkrGateway} 负责转过来，订阅回调也由 {@link IbkrSubscriptions}
 * 转到 dispatch），所以状态不加锁。
 *
 * <p>实测依据（2026-09-19 只读探针）：
 * <ul>
 *   <li>账户汇总首批约 0.3 秒回齐，之后<b>逐条</b>推送、不再有 End，约 3 分钟一批 → 逐条攒起来、静默 {@value #SUMMARY_DEBOUNCE_MS} ms 后发一次；</li>
 *   <li>reqPnL <b>首条不对</b>（新会话只含一只持仓；同一会话退订再订给出的也不对），约 1 秒后第二条才对 → 丢掉首条。
 *       但第二条不一定来：生产 09-18 20:37 订上后 20 分钟没再推（盘后结束、价格静止）→ {@value #PNL_WATCHDOG_SECONDS} 秒还没有有效值就重订，
 *       最多 {@value #PNL_MAX_RETRIES} 次（重订时逐只盈亏已在跑，探针里第二条 1 秒就到）；</li>
 *   <li>持仓标的行情（reqMktData）取 lastPrice：账户有实时权限；收盘后买卖价为 -1；最新价不等于"市值 ÷ 数量"（GOOG 346.08 对 344.41）；</li>
 *   <li>持仓 End 之后成交会再推变动行，数量为 0 表示清仓 → 维护完整列表，每次变动发一次全量，并同步逐只盈亏的订阅。</li>
 * </ul>
 * 1101（连接恢复、订阅数据丢失）时会话不变，旧订阅还挂在网关上：先取消再重订，否则账户汇总会撞上每客户端 2 个的上限（322）。
 */
final class IbkrLiveAccount {

    private static final Logger log = LoggerFactory.getLogger(IbkrLiveAccount.class);
    static final long SUMMARY_DEBOUNCE_MS = 300;
    static final long PNL_WATCHDOG_SECONDS = 10;
    static final int PNL_MAX_RETRIES = 3;

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
    private final Map<String, Sub> quotes = new LinkedHashMap<>();
    private final Map<String, Boolean> delayed = new LinkedHashMap<>();

    private final Map<String, IbkrAccounts.PositionRow> positions = new LinkedHashMap<>();
    private boolean positionsReady;
    private final Map<String, IbkrAccounts.SummaryRow> summaryRows = new LinkedHashMap<>();
    private Runnable summaryFlush;
    private boolean pnlFirstSeen;
    private boolean pnlValid;
    private int pnlRetries;
    private Runnable pnlWatchdog;

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
        pnlValid = false;
        pnlRetries = 0;

        positionsSub = open("实时持仓", positionsHandler(),
                id -> c -> c.reqPositionsMulti(id, accountId, ""), id -> c -> c.cancelPositionsMulti(id));
        summarySub = open("实时账户汇总", summaryHandler(),
                id -> c -> c.reqAccountSummary(id, "All", IbkrAccounts.SUMMARY_TAGS), id -> c -> c.cancelAccountSummary(id));
        pnlSub = openPnl();
        if (positionsSub == null || summarySub == null || pnlSub == null) {
            cancelAll();        // 发一半就断了：退掉已发的，等下一次连上整组重订
            bound = null;
            return false;
        }
        schedulePnlWatchdog();
        return true;
    }

    private Sub openPnl() {
        pnlFirstSeen = false;
        return open("实时账户盈亏", pnlHandler(), id -> c -> c.reqPnL(id, accountId, ""), id -> c -> c.cancelPnL(id));
    }

    private void schedulePnlWatchdog() {
        pnlWatchdog = wire.later(Duration.ofSeconds(PNL_WATCHDOG_SECONDS), this::checkPnl);
    }

    /** 账户盈亏迟迟没有有效值（首条已丢、第二条不来）：退订再订，最多 {@value #PNL_MAX_RETRIES} 次。 */
    void checkPnl() {
        pnlWatchdog = null;
        if (bound == null || pnlValid || pnlRetries >= PNL_MAX_RETRIES) {
            return;
        }
        pnlRetries++;
        log.info("实时账户盈亏 {} 秒没有有效推送，重订（第 {} 次）", PNL_WATCHDOG_SECONDS, pnlRetries);
        cancel(pnlSub);
        pnlSub = openPnl();
        if (pnlSub != null && pnlRetries < PNL_MAX_RETRIES) {
            schedulePnlWatchdog();
        }
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

    int quoteCount() {
        return quotes.size();
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
        quotes.values().forEach(this::cancel);
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
        all.addAll(quotes.values());
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
        quotes.clear();
        delayed.clear();
        if (pnlWatchdog != null) {
            pnlWatchdog.run();   // 取消
            pnlWatchdog = null;
        }
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

    /** 逐只盈亏与行情的订阅跟着持仓走：新出现的订、清仓的退。 */
    private void syncSingles() {
        for (String conId : new ArrayList<>(singles.keySet())) {
            if (!positions.containsKey(conId)) {
                cancel(singles.remove(conId));
            }
        }
        for (String conId : new ArrayList<>(quotes.keySet())) {
            if (!positions.containsKey(conId)) {
                cancel(quotes.remove(conId));
                delayed.remove(conId);
            }
        }
        for (Map.Entry<String, IbkrAccounts.PositionRow> e : positions.entrySet()) {
            String conId = e.getKey();
            int con = Integer.parseInt(conId);
            if (!singles.containsKey(conId)) {
                Sub s = open("逐只盈亏 " + conId, singleHandler(conId),
                        id -> c -> c.reqPnLSingle(id, accountId, "", con), id -> c -> c.cancelPnLSingle(id));
                if (s != null) {
                    singles.put(conId, s);
                }
            }
            if (!quotes.containsKey(conId)) {
                Contract src = e.getValue().contract();
                Contract contract = new Contract();
                contract.conid(con);
                contract.exchange("SMART");
                contract.secType(src.getSecType());
                contract.currency(src.currency());
                Sub q = open("持仓行情 " + conId, quoteHandler(conId),
                        id -> c -> c.reqMktData(id, contract, "", false, false, null), id -> c -> c.cancelMktData(id));
                if (q != null) {
                    quotes.put(conId, q);
                }
            }
        }
    }

    private IbkrSubscriptions.Handler quoteHandler(String conId) {
        return new IbkrSubscriptions.Handler() {
            @Override
            public void item(Object item) {
                if (item instanceof IbkrAccounts.MarketDataTypeRow t) {
                    delayed.put(conId, t.type() == 3 || t.type() == 4);
                } else if (item instanceof IbkrAccounts.TickRow t) {
                    var p = IbkrAccounts.lastPrice(conId, t, delayed.getOrDefault(conId, false), wire.now());
                    if (p != null) {
                        listener.onPositionPrice(p);
                    }
                }
            }

            @Override
            public void error(RequestRejectedException e) {
                listener.onLiveError("持仓行情", e);
            }
        };
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
                    pnlFirstSeen = true;   // 首条不对（实测只含一只持仓，或给出一个错的数），丢掉
                    return;
                }
                pnlValid = true;
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
