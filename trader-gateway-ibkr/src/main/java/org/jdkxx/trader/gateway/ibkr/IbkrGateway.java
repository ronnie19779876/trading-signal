package org.jdkxx.trader.gateway.ibkr;

import org.jdkxx.trader.common.ratelimit.TokenBucketRateLimiter;
import org.jdkxx.trader.domain.AccountKind;
import org.jdkxx.trader.domain.AccountRef;
import org.jdkxx.trader.domain.AccountSummary;
import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.InstrumentInfo;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.Position;
import org.jdkxx.trader.gateway.AccountGateway;
import org.jdkxx.trader.gateway.BrokerGateway;
import org.jdkxx.trader.gateway.GatewayException;
import org.jdkxx.trader.gateway.GatewayListener;
import org.jdkxx.trader.gateway.GatewayStatus;
import org.jdkxx.trader.gateway.LiveAccountGateway;
import org.jdkxx.trader.gateway.LiveAccountListener;
import org.jdkxx.trader.gateway.NotConnectedException;
import org.jdkxx.trader.gateway.ReferenceDataGateway;
import org.jdkxx.trader.gateway.RequestRejectedException;
import org.jdkxx.trader.gateway.ibkr.mapper.IbkrAccounts;
import org.jdkxx.trader.gateway.ibkr.mapper.IbkrContracts;
import org.jdkxx.trader.gateway.ibkr.mapper.IbkrInstruments;
import org.jdkxx.trader.gateway.support.ConnectionSupervisor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 盈透网关适配器的对外入口：生命周期由 {@link ConnectionSupervisor} 驱动，会话由 {@link IbkrConnection} 持有。
 * 能力：连接 / 重连 / 心跳、受管账户、合约查询；第 3 期起加持仓与账户汇总（只读）；3.0.2 起加实时账户订阅。
 */
public class IbkrGateway implements BrokerGateway, ReferenceDataGateway, AccountGateway, LiveAccountGateway, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(IbkrGateway.class);

    private final IbkrProperties props;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService dispatch;
    private final IbkrFacts facts = new IbkrFacts();
    private final IbkrConnection connection;
    private final IbkrSubscriptions subscriptions;
    private final ConnectionSupervisor supervisor;
    /** 账户汇总的常驻订阅：随连接建立、随断开释放，实时账户与每日快照共用（3.0.9）。只在 dispatch 线程上读写。 */
    private IbkrAccountSummaryFeed summaryFeed;
    /** 实时账户订阅：只在 dispatch 线程上读写。 */
    private IbkrLiveAccount live;
    private volatile boolean liveIntent;

    public IbkrGateway(IbkrProperties props) {
        props.validate();
        this.props = props;
        if (!props.enabled()) {
            scheduler = null;
            dispatch = null;
            connection = null;
            subscriptions = null;
            supervisor = null;
            return;
        }
        scheduler = Executors.newScheduledThreadPool(2, named("ibkr-scheduler"));
        dispatch = Executors.newSingleThreadExecutor(named("ibkr-dispatch"));
        IbkrRequestRegistry registry = new IbkrRequestRegistry(scheduler, dispatch, props.requestTimeout());
        subscriptions = new IbkrSubscriptions(dispatch);
        connection = new IbkrConnection(props, registry, subscriptions, facts, dispatch,
                new TokenBucketRateLimiter("ibkr-messages", props.messageRatePerSecond(),
                        props.messageRatePerSecond(), Duration.ofSeconds(30)));
        supervisor = new ConnectionSupervisor(Broker.IBKR, "盈透网关", connection, props.supervisorSettings(), scheduler);
        connection.onClosed(supervisor::onTransportClosed);
        connection.onDataLost(supervisor::notifyDataLost);
        supervisor.addListener(new GatewayListener() {
            @Override
            public void onConnected(Broker broker, boolean reconnected) {
                verifyConfiguredAccount();
                // 首次连上、断线重连、1101 数据丢失都走这里
                dispatch.execute(() -> {
                    summaryFeed().subscribe();      // 账户汇总常驻：一个连接周期只订一次
                    if (live != null) {
                        live.subscribe();
                    }
                });
            }

            @Override
            public void onDisconnected(Broker broker, String reason) {
                dispatch.execute(() -> {
                    if (summaryFeed != null) {
                        summaryFeed.stop();     // 等待中的快照请求立刻失败，不干等 90 秒
                    }
                });
            }
        });
    }

    private static java.util.concurrent.ThreadFactory named(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    /**
     * 配置了 trader.ibkr.account 时它必须在受管账户列表里，否则是不可重试的配置错误。
     * 列表还没到就主动要一次：2.0.2 前列表为空时直接放行，之后也不补查。
     */
    private void verifyConfiguredAccount() {
        if (props.account() == null || props.account().isBlank()) {
            return;
        }
        String wanted = props.account().trim();
        connection.managedAccounts()
                .orTimeout(props.requestTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .whenCompleteAsync((accounts, ex) -> {
                    if (ex != null) {
                        // 拿不到列表不能当作通过：断开重连，连上后再核对
                        supervisor.onTransportClosed("核对受管账户列表失败：" + causeMessage(ex));
                    } else if (accounts.isEmpty()) {
                        supervisor.reportFatal(new GatewayException(Broker.IBKR, 0,
                                "网关没有返回受管账户列表，无法核对 trader.ibkr.account，拒绝继续", false));
                    } else if (!accounts.contains(wanted)) {
                        supervisor.reportFatal(new GatewayException(Broker.IBKR, 0,
                                "配置的 trader.ibkr.account 不在网关的受管账户列表里，拒绝继续（请核对账户号）", false));
                    }
                }, scheduler);
    }

    private static String causeMessage(Throwable t) {
        Throwable c = t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
        return c.getMessage() == null || c.getMessage().isBlank() ? c.getClass().getSimpleName() : c.getMessage();
    }

    @Override
    public Broker broker() {
        return Broker.IBKR;
    }

    @Override
    public boolean enabled() {
        return props.enabled();
    }

    @Override
    public boolean autoConnect() {
        return props.autoConnect();
    }

    @Override
    public GatewayStatus status() {
        if (!props.enabled()) {
            return GatewayStatus.disabled("未启用（trader.ibkr.enabled=false）");
        }
        return supervisor.status(facts.snapshot());
    }

    @Override
    public CompletableFuture<Void> connect() {
        if (!props.enabled()) {
            return CompletableFuture.completedFuture(null);
        }
        return supervisor.connect();
    }

    @Override
    public void disconnect() {
        if (props.enabled()) {
            supervisor.disconnect();
        }
    }

    @Override
    public CompletableFuture<List<AccountRef>> accounts() {
        if (!isConnected()) {
            return CompletableFuture.failedFuture(new NotConnectedException(Broker.IBKR, status().detail()));
        }
        return connection.managedAccounts().thenApply(ids -> ids.stream()
                .map(id -> new AccountRef(Broker.IBKR, id,
                        id.startsWith("DU") ? AccountKind.PAPER : AccountKind.LIVE, Set.of(Market.US)))
                .toList());
    }

    /** 网关侧的当前时间（reqCurrentTime），可用来核对本机与券商的时钟偏差。 */
    public CompletableFuture<java.time.Instant> serverTime() {
        if (!isConnected()) {
            return CompletableFuture.failedFuture(new NotConnectedException(Broker.IBKR, status().detail()));
        }
        return connection.currentTime();
    }

    @Override
    public CompletableFuture<List<InstrumentInfo>> lookup(Instrument instrument) {
        if (!isConnected()) {
            return CompletableFuture.failedFuture(new NotConnectedException(Broker.IBKR, status().detail()));
        }
        return connection.contractDetails(IbkrContracts.stock(instrument, props.primaryExchange()))
                .thenApply(list -> list.stream().map(cd -> IbkrInstruments.toInfo(instrument, cd)).toList())
                .exceptionally(ex -> {
                    Throwable c = ex instanceof CompletionException && ex.getCause() != null ? ex.getCause() : ex;
                    if (c instanceof RequestRejectedException r && r.code() == 200) {
                        return List.of();   // 200 = 没有这个证券定义 → 查无此标的
                    }
                    throw c instanceof RuntimeException re ? re : new CompletionException(c);
                });
    }

    @Override
    public CompletableFuture<List<Position>> positions(String accountId) {
        requireAccountId(accountId);
        if (!isConnected()) {
            return CompletableFuture.failedFuture(new NotConnectedException(Broker.IBKR, status().detail()));
        }
        return connection.positions(accountId).thenApply(rows -> IbkrAccounts.positions(accountId, rows));
    }

    /**
     * 账户汇总：读<b>常驻订阅</b>的最新值（3.0.9 改），不再每次自己发 {@code reqAccountSummary}。
     *
     * <p>盈透的每客户端 2 个上限算的是订过的次数、取消不释放名额（2026-09-23 生产实测，见
     * {@link IbkrAccountSummaryFeed}），所以"用完就退"这条路走不通：快照作业每天订一次，
     * 迟早把名额耗光，之后连实时账户都订不上。现在整个连接周期只订一次，两边共用。
     *
     * <p>代价：拿到的是最近一次推送的值（券商约 3 分钟一批），不是"当场查"。还没收到首批时
     * 最多等 {@link IbkrAccountSummaryFeed#WAIT}，超时抛异常由调用方处理（快照作业记 FAILED，21:00 补偿重试）。
     */
    @Override
    public CompletableFuture<AccountSummary> accountSummary(String accountId) {
        requireAccountId(accountId);
        if (!isConnected()) {
            return CompletableFuture.failedFuture(new NotConnectedException(Broker.IBKR, status().detail()));
        }
        CompletableFuture<AccountSummary> out = new CompletableFuture<>();
        dispatch.execute(() -> summaryFeed().request(accountId, out));
        return out;
    }

    /** 常驻汇总订阅，懒建；只在 dispatch 线程上调用。 */
    private IbkrAccountSummaryFeed summaryFeed() {
        if (summaryFeed == null) {
            summaryFeed = new IbkrAccountSummaryFeed(new LiveWire());
        }
        return summaryFeed;
    }

    // ------------------------------------------------------------------ 实时账户

    @Override
    public void startLive(String accountId, LiveAccountListener listener) {
        requireAccountId(accountId);
        if (!props.enabled()) {
            return;
        }
        liveIntent = true;
        dispatch.execute(() -> {
            summaryFeed().listener(accountId, listener);   // 资金由常驻订阅供给，已有数据会立刻补一条
            summaryFeed().subscribe();
            if (live != null && live.accountId.equals(accountId)) {
                live.listener(listener);
                if (!live.subscribed() && isConnected()) {
                    live.subscribe();
                }
                return;
            }
            if (live != null) {
                live.stop();
            }
            live = new IbkrLiveAccount(accountId, listener, new LiveWire());
            if (isConnected()) {
                live.subscribe();
            }
        });
    }

    @Override
    public void stopLive() {
        if (!props.enabled()) {
            return;
        }
        liveIntent = false;
        dispatch.execute(() -> {
            if (summaryFeed != null) {
                summaryFeed.listener(null, null);   // 只摘监听器，常驻订阅不退——退了名额也不还
            }
            if (live != null) {
                live.stop();
                live = null;
            }
        });
    }

    @Override
    public boolean liveActive() {
        return liveIntent;
    }

    /** {@link IbkrLiveAccount} 与连接之间的接缝。 */
    private final class LiveWire implements IbkrLiveAccount.Wire {
        @Override
        public Object sessionToken() {
            return connection.sessionToken();
        }

        @Override
        public int nextId() {
            return connection.nextId();
        }

        @Override
        public boolean subscribe(String what, java.util.function.Consumer<com.ib.client.EClientSocket> request) {
            return connection.subscribe(what, request);
        }

        @Override
        public void unsubscribe(String what, java.util.function.Consumer<com.ib.client.EClientSocket> cancel) {
            connection.unsubscribe(what, cancel);
        }

        @Override
        public void open(int id, IbkrSubscriptions.Handler handler) {
            subscriptions.open(id, handler);
        }

        @Override
        public void close(int id) {
            subscriptions.close(id);
        }

        @Override
        public Runnable later(Duration delay, Runnable task) {
            java.util.concurrent.ScheduledFuture<?> f = scheduler.schedule(() -> dispatch.execute(task),
                    delay.toMillis(), TimeUnit.MILLISECONDS);
            return () -> f.cancel(false);
        }

        @Override
        public Instant now() {
            return Instant.now();
        }
    }

    private static void requireAccountId(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("accountId 不能为空");
        }
    }

    @Override
    public void addListener(GatewayListener listener) {
        if (props.enabled()) {
            supervisor.addListener(listener);
        }
    }

    private boolean isConnected() {
        return props.enabled() && supervisor.isConnected() && connection.isConnected();
    }

    @Override
    public void close() {
        if (!props.enabled()) {
            return;
        }
        try {
            supervisor.disconnect();
        } catch (RuntimeException e) {
            log.warn("断开盈透网关时出错：{}", e.toString());
        }
        connection.shutdown();
        dispatch.shutdown();
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
