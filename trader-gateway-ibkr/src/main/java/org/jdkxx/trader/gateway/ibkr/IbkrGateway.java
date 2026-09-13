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
 * 能力：连接 / 重连 / 心跳、受管账户、合约查询；第 3 期起加持仓与账户汇总（只读）。
 */
public class IbkrGateway implements BrokerGateway, ReferenceDataGateway, AccountGateway, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(IbkrGateway.class);

    private final IbkrProperties props;
    /** 进行中的账户汇总请求，按账户合并并发调用。 */
    private final ConcurrentHashMap<String, CompletableFuture<AccountSummary>> summaries = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;
    private final ExecutorService dispatch;
    private final IbkrFacts facts = new IbkrFacts();
    private final IbkrConnection connection;
    private final ConnectionSupervisor supervisor;

    public IbkrGateway(IbkrProperties props) {
        props.validate();
        this.props = props;
        if (!props.enabled()) {
            scheduler = null;
            dispatch = null;
            connection = null;
            supervisor = null;
            return;
        }
        scheduler = Executors.newScheduledThreadPool(2, named("ibkr-scheduler"));
        dispatch = Executors.newSingleThreadExecutor(named("ibkr-dispatch"));
        IbkrRequestRegistry registry = new IbkrRequestRegistry(scheduler, dispatch, props.requestTimeout());
        connection = new IbkrConnection(props, registry, facts, dispatch,
                new TokenBucketRateLimiter("ibkr-messages", props.messageRatePerSecond(),
                        props.messageRatePerSecond(), Duration.ofSeconds(30)));
        supervisor = new ConnectionSupervisor(Broker.IBKR, "盈透网关", connection, props.supervisorSettings(), scheduler);
        connection.onClosed(supervisor::onTransportClosed);
        connection.onDataLost(supervisor::notifyDataLost);
        supervisor.addListener(new GatewayListener() {
            @Override
            public void onConnected(Broker broker, boolean reconnected) {
                verifyConfiguredAccount();
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

    /** 配置了 trader.ibkr.account 时它必须在受管账户列表里，否则是不可重试的配置错误。 */
    private void verifyConfiguredAccount() {
        if (props.account() == null || props.account().isBlank()) {
            return;
        }
        List<String> accounts = facts.managedAccounts();
        if (!accounts.isEmpty() && !accounts.contains(props.account().trim())) {
            supervisor.reportFatal(new GatewayException(Broker.IBKR, 0,
                    "配置的 trader.ibkr.account 不在网关的受管账户列表里，拒绝继续（请核对账户号）", false));
        }
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
     * 同一账户的并发调用合并成一次券商请求：网关对账户汇总订阅有全局并发上限（官方文档为 2），
     * 反复请求-取消还会被警告。每个调用方拿到各自的 future 副本，互不影响。
     */
    @Override
    public CompletableFuture<AccountSummary> accountSummary(String accountId) {
        requireAccountId(accountId);
        if (!isConnected()) {
            return CompletableFuture.failedFuture(new NotConnectedException(Broker.IBKR, status().detail()));
        }
        CompletableFuture<AccountSummary> mine = new CompletableFuture<>();
        CompletableFuture<AccountSummary> running = summaries.putIfAbsent(accountId, mine);
        if (running != null) {
            return running.copy();
        }
        connection.accountSummary(IbkrAccounts.SUMMARY_TAGS)
                .thenApply(rows -> IbkrAccounts.summary(accountId, rows, Instant.now()))
                .whenComplete((summary, ex) -> {
                    summaries.remove(accountId, mine);
                    if (ex == null) {
                        mine.complete(summary);
                    } else {
                        mine.completeExceptionally(ex instanceof CompletionException && ex.getCause() != null ? ex.getCause() : ex);
                    }
                });
        return mine.copy();
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
