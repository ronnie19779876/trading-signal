package org.jdkxx.trader.gateway.ibkr;

import com.ib.client.EClientSocket;
import org.jdkxx.trader.domain.AccountSummary;
import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.gateway.GatewayException;
import org.jdkxx.trader.gateway.NotConnectedException;
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
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 账户汇总的<b>常驻订阅</b>：连上就订、断开才退，整个连接周期只订一次，实时账户与每日快照共用这一条。
 *
 * <p>为什么不再"用完就退"（2026-09-23 生产实测，3.0.9 改）：盈透的每客户端 2 个上限算的是
 * <b>订过的次数</b>而不是当前活着的订阅——{@code cancelAccountSummary} 发出去了，名额却不释放，
 * 只有客户端重连才清零。当天的时间线逐格吻合：22:04 实时账户闲置退订占 1 个 →
 * 次日 06:00 快照作业订到第 2 个（所以作业本身成功）→ 06:53 实时账户再订就是第 3 个 → 322，
 * 此后每次再订都被拒，直到人工断开重连。3.0.8 的"订阅前补发取消"救不了这个场景：
 * 它只记得实时账户自己上一次的 reqId，取消不掉快照作业留下的那一个。
 *
 * <p><b>这是规避不是修复</b>：盈透为什么不释放名额仍然不知道。这里只保证我们一个连接周期内只订一次，
 * 让"释放与否"不再影响正确性。1101 数据丢失后的重订仍会再占一个名额，那一次若被拒只能重连——待实测。
 */
final class IbkrAccountSummaryFeed {

    private static final Logger log = LoggerFactory.getLogger(IbkrAccountSummaryFeed.class);

    /** 账户汇总撞上每客户端 2 个上限时的错误码 */
    static final int LIMIT_CODE = 322;
    static final long RETRY_SECONDS = 3;
    static final int MAX_RETRIES = 3;
    /** 逐条推送攒批：券商一条一个标签地推，没有 End。 */
    static final long DEBOUNCE_MS = 300;
    /** 快照取数可接受的陈旧度：券商约 3 分钟推一批（实测），留三倍余量。 */
    static final Duration MAX_AGE = Duration.ofMinutes(10);
    /** 还没收到首批时最多等多久（刚重连的情形）。 */
    static final Duration WAIT = Duration.ofSeconds(90);

    private final IbkrLiveAccount.Wire wire;

    /** 订上时所在的会话；null = 当前没有订阅 */
    private Object bound;
    private Integer subId;
    /** 上一次的请求 id，跨会话保留：重订前补发一次取消（无害，盈透不认识就只回一条系统消息）。 */
    private Integer staleId;
    private int retries;
    private Runnable retry;
    private Runnable flush;

    private final Map<String, IbkrAccounts.SummaryRow> rows = new LinkedHashMap<>();
    private List<IbkrAccounts.SummaryRow> snapshot = List.of();
    private Instant updatedAt;
    private String lastError;

    /** 实时账户的监听器与它关心的账户，都可空（没人在看实时账户时照样常驻订阅）。 */
    private LiveAccountListener listener;
    private String liveAccountId;
    private final List<Waiter> waiters = new ArrayList<>();

    private record Waiter(String accountId, CompletableFuture<AccountSummary> future, Runnable cancelTimeout) {
    }

    IbkrAccountSummaryFeed(IbkrLiveAccount.Wire wire) {
        this.wire = wire;
    }

    /**
     * 挂上 / 摘下实时账户的监听器（{@code accountId} 是它关心的账户）。
     * 挂上时若已有数据立刻补一条，免得等下一批——常驻订阅的好处正在于此：闲置退订再读也不用等 3 分钟。
     */
    void listener(String accountId, LiveAccountListener listener) {
        this.liveAccountId = accountId;
        this.listener = listener;
        if (listener != null && accountId != null && !snapshot.isEmpty()) {
            notifyListener();
        }
    }

    boolean subscribed() {
        return bound != null && bound == wire.sessionToken() && subId != null;
    }

    String lastError() {
        return lastError;
    }

    Instant updatedAt() {
        return updatedAt;
    }

    /** 订阅。已经订着就什么都不做——<b>常驻的意义就在于不重复订</b>。 */
    boolean subscribe() {
        if (subscribed()) {
            return true;
        }
        Object current = wire.sessionToken();
        if (current == null) {
            return false;
        }
        if (bound != null && bound == current && subId != null) {
            return true;
        }
        forget();
        bound = current;
        retries = 0;
        cancelStale();
        return open();
    }

    /** 断开 / 关停时退订。 */
    void stop() {
        if (bound != null && bound == wire.sessionToken() && subId != null) {
            wire.close(subId);
            int id = subId;
            wire.unsubscribe("账户汇总（常驻）", c -> c.cancelAccountSummary(id));
        } else {
            forget();
        }
        subId = null;
        bound = null;
        cancelRetry();
        failWaiters(new NotConnectedException(Broker.IBKR, "已断开，账户汇总常驻订阅已退订"));
    }

    /**
     * 取账户汇总：新鲜就直接给，还没有或太旧就等下一批（最多 {@link #WAIT}）。
     * <b>只在 dispatch 线程上调用。</b>
     */
    void request(String accountId, CompletableFuture<AccountSummary> out) {
        if (!subscribe()) {
            out.completeExceptionally(new NotConnectedException(Broker.IBKR, "账户汇总常驻订阅没建起来"));
            return;
        }
        if (fresh()) {
            complete(accountId, out);
            return;
        }
        Runnable cancelTimeout = wire.later(WAIT, () -> timeout(out));
        waiters.add(new Waiter(accountId, out, cancelTimeout));
    }

    private boolean fresh() {
        return updatedAt != null && !snapshot.isEmpty()
                && Duration.between(updatedAt, wire.now()).compareTo(MAX_AGE) <= 0;
    }

    private void complete(String accountId, CompletableFuture<AccountSummary> out) {
        try {
            out.complete(IbkrAccounts.summary(accountId, new ArrayList<>(snapshot), updatedAt));
        } catch (RuntimeException e) {
            out.completeExceptionally(e);
        }
    }

    private void timeout(CompletableFuture<AccountSummary> out) {
        waiters.removeIf(w -> w.future() == out);
        out.completeExceptionally(new GatewayException(Broker.IBKR, 0,
                "账户汇总常驻订阅 " + WAIT.toSeconds() + " 秒内没有推送"
                        + (lastError == null ? "" : "：" + lastError), true));
    }

    private boolean open() {
        int id = wire.nextId();
        wire.open(id, handler());
        if (!wire.subscribe("账户汇总（常驻）",
                c -> c.reqAccountSummary(id, "All", IbkrAccounts.SUMMARY_TAGS))) {
            wire.close(id);
            subId = null;
            bound = null;
            return false;
        }
        subId = id;
        staleId = id;
        return true;
    }

    /** 盈透侧可能还挂着上一次的订阅：按记下的 id 发一次取消。 */
    private void cancelStale() {
        Integer id = staleId;
        if (id == null) {
            return;
        }
        staleId = null;
        wire.unsubscribe("账户汇总（上一次）", (Consumer<EClientSocket>) c -> c.cancelAccountSummary(id));
    }

    /** 被 322 拒：退掉这条、连同残留的一起取消，隔几秒重订，最多 {@value #MAX_RETRIES} 次。 */
    void retrySubscribe() {
        retry = null;
        if (bound == null || bound != wire.sessionToken() || retries >= MAX_RETRIES) {
            return;
        }
        retries++;
        log.info("账户汇总常驻订阅被 {} 拒，重订（第 {} 次）", LIMIT_CODE, retries);
        if (subId != null) {
            wire.close(subId);
            int id = subId;
            if (staleId != null && staleId == id) {
                staleId = null;             // 同一个 id 别取消两次
            }
            wire.unsubscribe("账户汇总（常驻）", c -> c.cancelAccountSummary(id));
            subId = null;
        }
        cancelStale();
        open();
    }

    private void forget() {
        if (subId != null) {
            wire.close(subId);
        }
        subId = null;
        cancelRetry();
    }

    private void cancelRetry() {
        if (retry != null) {
            retry.run();
            retry = null;
        }
        if (flush != null) {
            flush.run();
            flush = null;
        }
    }

    private void failWaiters(GatewayException e) {
        List<Waiter> pending = new ArrayList<>(waiters);
        waiters.clear();
        for (Waiter w : pending) {
            w.cancelTimeout().run();
            w.future().completeExceptionally(e);
        }
    }

    private IbkrSubscriptions.Handler handler() {
        return new IbkrSubscriptions.Handler() {
            @Override
            public void item(Object item) {
                IbkrAccounts.SummaryRow r = (IbkrAccounts.SummaryRow) item;
                if (r.tag() == null) {
                    return;
                }
                rows.put(r.account() + "|" + r.tag(), r);   // "All" 组可能含多个账户，按账户 + 标签存
                if (flush == null) {
                    flush = wire.later(Duration.ofMillis(DEBOUNCE_MS), IbkrAccountSummaryFeed.this::flushNow);
                }
            }

            @Override
            public void end() {
                if (flush != null) {
                    flush.run();
                    flush = null;
                }
                flushNow();
            }

            @Override
            public void error(RequestRejectedException e) {
                lastError = e.getMessage();
                if (listener != null) {
                    listener.onLiveError("账户汇总", e);
                }
                if (e.code() == LIMIT_CODE && retry == null && retries < MAX_RETRIES) {
                    retry = wire.later(Duration.ofSeconds(RETRY_SECONDS), IbkrAccountSummaryFeed.this::retrySubscribe);
                }
            }
        };
    }

    private void flushNow() {
        flush = null;
        if (rows.isEmpty()) {
            return;
        }
        snapshot = List.copyOf(rows.values());
        updatedAt = wire.now();
        lastError = null;
        notifyListener();
        List<Waiter> pending = new ArrayList<>(waiters);
        waiters.clear();
        for (Waiter w : pending) {
            w.cancelTimeout().run();
            complete(w.accountId(), w.future());
        }
    }

    private void notifyListener() {
        LiveAccountListener l = listener;
        if (l == null || liveAccountId == null) {
            return;
        }
        try {
            l.onSummary(IbkrAccounts.summary(liveAccountId, new ArrayList<>(snapshot), updatedAt));
        } catch (GatewayException e) {
            l.onLiveError("账户汇总", e);
        }
    }
}
