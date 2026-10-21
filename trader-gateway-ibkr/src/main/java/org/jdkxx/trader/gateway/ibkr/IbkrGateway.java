package org.jdkxx.trader.gateway.ibkr;

import com.ib.client.Contract;
import org.jdkxx.trader.common.ratelimit.RateLimitExceededException;
import org.jdkxx.trader.common.ratelimit.RateLimitSpec;
import org.jdkxx.trader.common.ratelimit.RateLimiter;
import org.jdkxx.trader.common.ratelimit.SlidingWindowRateLimiter;
import org.jdkxx.trader.common.ratelimit.TokenBucketRateLimiter;
import org.jdkxx.trader.domain.AccountKind;
import org.jdkxx.trader.domain.AccountRef;
import org.jdkxx.trader.domain.AccountSummary;
import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.InstrumentInfo;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.Position;
import org.jdkxx.trader.domain.selection.SplitAdjustedBar;
import org.jdkxx.trader.gateway.AccountGateway;
import org.jdkxx.trader.gateway.BrokerGateway;
import org.jdkxx.trader.gateway.GatewayException;
import org.jdkxx.trader.gateway.GatewayListener;
import org.jdkxx.trader.gateway.GatewayStatus;
import org.jdkxx.trader.gateway.LiveAccountGateway;
import org.jdkxx.trader.gateway.LiveAccountListener;
import org.jdkxx.trader.gateway.NotConnectedException;
import org.jdkxx.trader.gateway.PriceHistoryGateway;
import org.jdkxx.trader.gateway.ReferenceDataGateway;
import org.jdkxx.trader.gateway.RequestRejectedException;
import org.jdkxx.trader.gateway.ibkr.mapper.IbkrAccounts;
import org.jdkxx.trader.gateway.ibkr.mapper.IbkrBars;
import org.jdkxx.trader.gateway.ibkr.mapper.IbkrContracts;
import org.jdkxx.trader.gateway.ibkr.mapper.IbkrInstruments;
import org.jdkxx.trader.gateway.support.ConnectionSupervisor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
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
public class IbkrGateway implements BrokerGateway, ReferenceDataGateway, AccountGateway, LiveAccountGateway, PriceHistoryGateway,
        AutoCloseable {

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
    /**
     * 历史数据节奏（量化选股，§25.6）：盈透规定 10 分钟内最多 60 次，留余量按 50 次；
     * 在调用方线程上等（作业线程，最长等一个窗口多一点），不占泵线程与 dispatch 线程。
     */
    private final RateLimiter historicalPacing = new SlidingWindowRateLimiter("ibkr-historical",
            RateLimitSpec.parse("50/10m"), Duration.ofSeconds(1), Duration.ofMinutes(11));
    /** 一次多年日线比普通请求慢，超时单独给。 */
    static final Duration HISTORICAL_TIMEOUT = Duration.ofSeconds(120);
    /** 最早数据时间实测 0.4~0.7 秒（SKYD、CRH、SW），给 30 秒足够。 */
    static final Duration HEAD_TIMESTAMP_TIMEOUT = Duration.ofSeconds(30);
    private static final DateTimeFormatter TWS_DAY = DateTimeFormatter.BASIC_ISO_DATE;

    /** 实时账户订阅：只在 dispatch 线程上读写。 */
    private IbkrLiveAccount live;
    private volatile boolean liveIntent;
    /**
     * 账户更新推送（3.4.6）：持仓均价与浮盈与盈透 App 同源。<b>只在生产实例启用</b>——同一账户同一时间只能有一个
     * API 客户端订它，开发实例订了会把生产挤掉。创建与订退在 dispatch 线程上；字段 volatile 供快照取持仓时读。
     */
    private final boolean portfolioFeedEnabled;
    private volatile IbkrPortfolioFeed portfolioFeed;

    public IbkrGateway(IbkrProperties props) {
        this(props, false);
    }

    /** @param portfolioFeedEnabled 是否订阅账户更新推送（只给生产实例：{@code trader.environment=PROD}） */
    public IbkrGateway(IbkrProperties props, boolean portfolioFeedEnabled) {
        props.validate();
        this.props = props;
        this.portfolioFeedEnabled = portfolioFeedEnabled && props.enabled();
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
                    // 账户汇总常驻：一个连接周期只订一次。但 1101（数据丢失）时会话不变、券商已丢弃订阅，
                    // 必须显式重订——reconnected 为 true 的两种情形都要传进去，见 subscribe(boolean)
                    summaryFeed().subscribe(reconnected);
                    IbkrPortfolioFeed pf = portfolioFeed();
                    if (pf != null) {
                        pf.subscribe(reconnected);
                    }
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
                    if (portfolioFeed != null) {
                        portfolioFeed.stop();
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
    public CompletableFuture<List<SplitAdjustedBar>> splitAdjustedDailyBars(long conId, LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("from 不能晚于 to：" + from + " > " + to));
        }
        if (!isConnected()) {
            return CompletableFuture.failedFuture(new NotConnectedException(Broker.IBKR, status().detail()));
        }
        try {
            historicalPacing.acquire();
        } catch (RateLimitExceededException e) {
            return CompletableFuture.failedFuture(e);
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        String duration = days <= 365 ? days + " D" : ((days + 364) / 365) + " Y";
        String end = to.format(TWS_DAY) + " 23:59:59 US/Eastern";
        return historicalContract(conId)
                .thenCompose(contract -> connection.historicalDailyBars(contract, end, duration, HISTORICAL_TIMEOUT))
                .thenApply(bars -> IbkrBars.toSplitAdjusted(bars, from, to));
    }

    /** 盈透把最早数据时间算进历史数据节奏（10 分钟 60 次），与历史日线共用限流。 */
    @Override
    public CompletableFuture<LocalDate> earliestDailyBar(long conId) {
        if (!isConnected()) {
            return CompletableFuture.failedFuture(new NotConnectedException(Broker.IBKR, status().detail()));
        }
        try {
            historicalPacing.acquire();
        } catch (RateLimitExceededException e) {
            return CompletableFuture.failedFuture(e);
        }
        return historicalContract(conId)
                .thenCompose(contract -> connection.headTimestamp(contract, HEAD_TIMESTAMP_TIMEOUT))
                .thenApply(ts -> {
                    LocalDate day = IbkrBars.headDay(ts);
                    if (day == null) {
                        throw new IllegalStateException("盈透最早数据时间解析不了：" + ts);
                    }
                    return day;
                });
    }

    /**
     * 取历史用的合约：按主交易所，不用 SMART。SMART 只给转板之后的历史（2026-10-11 实测 PEP 2017-12-20 由纽交所转纳斯达克，
     * SMART 只有 132 根、NASDAQ 给 2015 起 755 根且跨过转板日；WDAY、COO 同样）。KO、AAPL 两种取法逐根相同。
     */
    private CompletableFuture<Contract> historicalContract(long conId) {
        return connection.contractDetails(IbkrContracts.byConId(conId))
                .thenApply(details -> {
                    String primary = details.isEmpty() || details.getFirst().contract() == null ? null
                            : details.getFirst().contract().primaryExch();
                    return IbkrContracts.historical(conId, primary);
                });
    }

    @Override
    public CompletableFuture<List<Position>> positions(String accountId) {
        requireAccountId(accountId);
        if (!isConnected()) {
            return CompletableFuture.failedFuture(new NotConnectedException(Broker.IBKR, status().detail()));
        }
        return connection.positions(accountId).thenApply(rows -> withPortfolioCost(IbkrAccounts.positions(accountId, rows)));
    }

    /**
     * 每日快照取持仓时，均价同样优先取账户更新推送（数量一致时）：2026-10-20 加仓当天的快照按旧均价存了成本。
     * 推送没订上（开发实例）或还没到齐时原样返回。
     */
    private List<Position> withPortfolioCost(List<Position> list) {
        IbkrPortfolioFeed pf = portfolioFeed;
        java.util.Map<String, IbkrAccounts.PortfolioRow> rows = pf == null ? java.util.Map.of() : pf.rows();
        if (rows.isEmpty()) {
            return list;
        }
        List<Position> out = new java.util.ArrayList<>(list.size());
        for (Position p : list) {
            java.math.BigDecimal cost = IbkrPortfolioFeed.cost(rows, p.brokerRef(), p.quantity(), p.averageCost(),
                    p.symbol(), pf.costDiffLogged());
            out.add(cost == null ? p : new Position(p.broker(), p.accountId(), p.brokerRef(), p.symbol(), p.localSymbol(),
                    p.securityType(), p.exchange(), p.primaryExchange(), p.currency(), p.quantity(), cost));
        }
        return List.copyOf(out);
    }

    /** 账户更新推送订哪个账户：配置的优先；没配时网关只下发了一个受管账户就用它，否则定不下（返回 null）。 */
    private String portfolioAccount() {
        if (props.account() != null && !props.account().isBlank()) {
            return props.account().trim();
        }
        List<String> managed = facts.managedAccounts();
        return managed.size() == 1 ? managed.get(0) : null;
    }

    /** 账户更新推送，懒建；未启用时为 null。只在 dispatch 线程上调用。 */
    private IbkrPortfolioFeed portfolioFeed() {
        if (!portfolioFeedEnabled) {
            return null;
        }
        if (portfolioFeed == null) {
            IbkrPortfolioFeed pf = new IbkrPortfolioFeed(new LiveWire(), this::portfolioAccount);
            pf.onChange(() -> {
                if (live != null) {
                    live.onPortfolioChanged();
                }
            });
            portfolioFeed = pf;
        }
        return portfolioFeed;
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
            IbkrPortfolioFeed pf = portfolioFeed();
            if (pf != null && isConnected() && !pf.subscribed()) {
                pf.subscribe(false, accountId);   // 连上时定不下账户（没配、受管列表还没到）：用实时账户的账户补订
            }
            if (live != null && live.accountId.equals(accountId)) {
                live.listener(listener);
                live.portfolio(portfolioFeed());
                if (!live.subscribed() && isConnected()) {
                    live.subscribe();
                }
                return;
            }
            if (live != null) {
                live.stop();
            }
            live = new IbkrLiveAccount(accountId, listener, new LiveWire());
            live.portfolio(portfolioFeed());
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
