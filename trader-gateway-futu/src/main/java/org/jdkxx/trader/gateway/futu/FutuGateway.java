package org.jdkxx.trader.gateway.futu;

import com.futu.openapi.pb.GetGlobalState;
import com.futu.openapi.pb.QotCommon;
import org.jdkxx.trader.common.ratelimit.RateLimiter;
import org.jdkxx.trader.common.ratelimit.SlidingWindowRateLimiter;
import org.jdkxx.trader.domain.AccountRef;
import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.HistoryQuota;
import org.jdkxx.trader.domain.CompanyProfile;
import org.jdkxx.trader.domain.FinancialReport;
import org.jdkxx.trader.domain.FinancialStatement;
import org.jdkxx.trader.domain.ValuationSnapshot;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.InstrumentStatic;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.MarketSession;
import org.jdkxx.trader.domain.Quote;
import org.jdkxx.trader.domain.RehabFactor;
import org.jdkxx.trader.domain.SubscriptionInfo;
import org.jdkxx.trader.domain.TradingDay;
import org.jdkxx.trader.gateway.BrokerGateway;
import org.jdkxx.trader.gateway.GatewayException;
import org.jdkxx.trader.gateway.GatewayListener;
import org.jdkxx.trader.gateway.GatewayState;
import org.jdkxx.trader.gateway.GatewayStatus;
import org.jdkxx.trader.gateway.MarketDataGateway;
import org.jdkxx.trader.gateway.NotConnectedException;
import org.jdkxx.trader.gateway.QuoteListener;
import org.jdkxx.trader.gateway.futu.mapper.FutuAccounts;
import org.jdkxx.trader.gateway.futu.mapper.FutuQuotes;
import org.jdkxx.trader.gateway.futu.mapper.FutuStates;
import org.jdkxx.trader.gateway.futu.marketdata.FutuMarketData;
import org.jdkxx.trader.gateway.support.ConnectionSupervisor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 富途网关适配器的对外入口。两条通道（行情 QOT、交易 TRD）各有自己的 {@link ConnectionSupervisor}，
 * 网关级状态是两者的合成：都 CONNECTED 才算 CONNECTED。监听器收到的是网关级事件（不会一条通道一次）。
 */
public class FutuGateway implements BrokerGateway, MarketDataGateway, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FutuGateway.class);

    private final FutuProperties props;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService dispatch;
    private final FutuChannel qot;
    private final FutuChannel trd;
    private final ConnectionSupervisor qotSupervisor;
    private final ConnectionSupervisor trdSupervisor;
    private final FutuMarketData marketData;
    private final ConcurrentHashMap<String, RateLimiter> limiters = new ConcurrentHashMap<>();
    private final List<GatewayListener> listeners = new CopyOnWriteArrayList<>();
    private final List<QuoteListener> quoteListeners = new CopyOnWriteArrayList<>();
    private volatile Map<String, String> stateFacts = Map.of();
    private volatile boolean up;
    private volatile boolean everUp;

    public FutuGateway(FutuProperties props) {
        props.validate();
        this.props = props;
        if (!props.enabled()) {
            scheduler = null;
            dispatch = null;
            qot = null;
            trd = null;
            qotSupervisor = null;
            trdSupervisor = null;
            marketData = null;
            return;
        }
        scheduler = Executors.newScheduledThreadPool(2, named("futu-scheduler"));
        dispatch = Executors.newSingleThreadExecutor(named("futu-dispatch"));
        qot = new FutuChannel(FutuChannel.Kind.QOT, props,
                new FutuReplyRegistry("行情", scheduler, dispatch, props.replyTimeout()), this::limiter);
        trd = new FutuChannel(FutuChannel.Kind.TRD, props,
                new FutuReplyRegistry("交易", scheduler, dispatch, props.replyTimeout()), this::limiter);
        qotSupervisor = new ConnectionSupervisor(Broker.FUTU, "富途行情通道", qot, props.supervisorSettings(), scheduler);
        trdSupervisor = new ConnectionSupervisor(Broker.FUTU, "富途交易通道", trd, props.supervisorSettings(), scheduler);
        marketData = new FutuMarketData(qot::qotCall);
        qot.onClosed(qotSupervisor::onTransportClosed);
        trd.onClosed(trdSupervisor::onTransportClosed);
        qot.onGlobalState(s -> stateFacts = FutuStates.facts(s));
        qot.onBasicQuote(rsp -> dispatch.execute(() -> deliverQuotes(rsp.getS2C().getBasicQotListList())));
        qotSupervisor.addListener(channelListener());
        trdSupervisor.addListener(channelListener());
    }

    private static java.util.concurrent.ThreadFactory named(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    private RateLimiter limiter(String name) {
        return limiters.computeIfAbsent(name, n ->
                new SlidingWindowRateLimiter("futu-" + n, props.limit(n), Duration.ofMillis(20), Duration.ofSeconds(30)));
    }

    /** 把两条通道的事件合成网关级事件。 */
    private GatewayListener channelListener() {
        return new GatewayListener() {
            @Override
            public void onConnected(Broker broker, boolean reconnected) {
                if (qotSupervisor.isConnected() && qot.isConnected()) {
                    // 连上就取一次全局状态，让 facts 立刻可用，不必等第一次心跳
                    qot.globalState().exceptionally(ex -> null);
                }
                if (qotSupervisor.isConnected() && trdSupervisor.isConnected() && !up) {
                    up = true;
                    boolean re = everUp;
                    everUp = true;
                    notifyListeners(l -> l.onConnected(Broker.FUTU, re));
                }
            }

            @Override
            public void onDisconnected(Broker broker, String reason) {
                if (up) {
                    up = false;
                    notifyListeners(l -> l.onDisconnected(Broker.FUTU, reason));
                }
            }

            @Override
            public void onError(Broker broker, GatewayException error) {
                notifyListeners(l -> l.onError(Broker.FUTU, error));
            }
        };
    }

    private void notifyListeners(Consumer<GatewayListener> action) {
        for (GatewayListener l : listeners) {
            try {
                action.accept(l);
            } catch (RuntimeException e) {
                log.warn("富途网关监听器抛出异常：{}", e.toString());
            }
        }
    }

    @Override
    public Broker broker() {
        return Broker.FUTU;
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
            return GatewayStatus.disabled("未启用（trader.futu.enabled=false）");
        }
        GatewayStatus q = qotSupervisor.status(Map.of());
        GatewayStatus t = trdSupervisor.status(Map.of());
        Map<String, String> facts = new TreeMap<>(stateFacts);
        facts.put("channel.qot", q.state().name());
        facts.put("channel.trd", t.state().name());
        GatewayState state = combine(q.state(), t.state());
        String detail;
        if (state == GatewayState.CONNECTED) {
            String ps = stateFacts.getOrDefault("programStatus", "");
            boolean qotLogined = Boolean.parseBoolean(stateFacts.getOrDefault("qotLogined", "true"));
            detail = !ps.isEmpty() && !"Ready".equals(ps) ? "已连接，但 OpenD 状态为 " + ps
                    : !qotLogined ? "已连接，但 OpenD 行情未登录"
                    : "已连接（行情 + 交易通道）";
        } else {
            detail = "行情通道：" + q.detail() + "；交易通道：" + t.detail();
        }
        Instant since = q.connectedSince() == null || t.connectedSince() == null ? null
                : (q.connectedSince().isAfter(t.connectedSince()) ? q.connectedSince() : t.connectedSince());
        Instant heartbeat = q.lastHeartbeatAt() == null ? t.lastHeartbeatAt()
                : t.lastHeartbeatAt() == null ? q.lastHeartbeatAt()
                : (q.lastHeartbeatAt().isBefore(t.lastHeartbeatAt()) ? q.lastHeartbeatAt() : t.lastHeartbeatAt());
        return new GatewayStatus(state, detail, Instant.now(), since, heartbeat,
                Math.max(q.reconnectAttempts(), t.reconnectAttempts()), facts);
    }

    static GatewayState combine(GatewayState a, GatewayState b) {
        if (a == GatewayState.ERROR || b == GatewayState.ERROR) {
            return GatewayState.ERROR;
        }
        if (a == GatewayState.CONNECTED && b == GatewayState.CONNECTED) {
            return GatewayState.CONNECTED;
        }
        if (a == GatewayState.RECONNECTING || b == GatewayState.RECONNECTING) {
            return GatewayState.RECONNECTING;
        }
        if (a == GatewayState.CONNECTING || b == GatewayState.CONNECTING) {
            return GatewayState.CONNECTING;
        }
        return GatewayState.DISCONNECTED;
    }

    @Override
    public CompletableFuture<Void> connect() {
        if (!props.enabled()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.allOf(qotSupervisor.connect(), trdSupervisor.connect());
    }

    @Override
    public void disconnect() {
        if (props.enabled()) {
            qotSupervisor.disconnect();
            trdSupervisor.disconnect();
        }
    }

    @Override
    public CompletableFuture<List<AccountRef>> accounts() {
        if (!props.enabled() || !trdSupervisor.isConnected() || !trd.isConnected()) {
            return CompletableFuture.failedFuture(new NotConnectedException(Broker.FUTU, status().detail()));
        }
        return trd.accList().thenApply(FutuAccounts::map);
    }

    /** OpenD 全局状态（行情通道就绪后可用）。 */
    public CompletableFuture<GetGlobalState.S2C> globalState() {
        if (!props.enabled() || !qotSupervisor.isConnected()) {
            return CompletableFuture.failedFuture(new NotConnectedException(Broker.FUTU, status().detail()));
        }
        return qot.globalState();
    }

    @Override
    public void addListener(GatewayListener listener) {
        listeners.add(listener);
    }

    // ------------------------------------------------------------------ MarketDataGateway（行情通道）

    private <T> CompletableFuture<T> requireQot(java.util.function.Supplier<CompletableFuture<T>> call) {
        if (!props.enabled() || !qotSupervisor.isConnected() || !qot.isConnected()) {
            return CompletableFuture.failedFuture(new NotConnectedException(Broker.FUTU, status().detail()));
        }
        return call.get();
    }

    @Override
    public CompletableFuture<List<InstrumentStatic>> staticInfo(List<Instrument> instruments) {
        return requireQot(() -> marketData.staticInfo(instruments));
    }

    @Override
    public CompletableFuture<HistoryQuota> historyQuota() {
        return requireQot(marketData::historyQuota);
    }

    @Override
    public CompletableFuture<List<DailyBar>> historyDailyBars(Instrument instrument, LocalDate from, LocalDate to) {
        return requireQot(() -> marketData.historyDailyBars(instrument, from, to));
    }

    /** 富途自己算的复权序列（1 前复权 / 2 后复权），只用于核对读取层复权。 */
    public CompletableFuture<List<DailyBar>> historyDailyBarsAdjusted(Instrument instrument, LocalDate from, LocalDate to, int rehabType) {
        return requireQot(() -> marketData.historyDailyBars(instrument, from, to, rehabType));
    }

    @Override
    public CompletableFuture<Void> subscribeDailyBars(List<Instrument> instruments) {
        return requireQot(() -> marketData.subscribeDailyBars(instruments));
    }

    @Override
    public CompletableFuture<Void> unsubscribeDailyBars(List<Instrument> instruments) {
        return requireQot(() -> marketData.unsubscribeDailyBars(instruments));
    }

    @Override
    public CompletableFuture<List<DailyBar>> recentDailyBars(Instrument instrument, int count) {
        return requireQot(() -> marketData.recentDailyBars(instrument, count));
    }

    @Override
    public CompletableFuture<List<RehabFactor>> rehab(Instrument instrument) {
        return requireQot(() -> marketData.rehab(instrument));
    }

    @Override
    public CompletableFuture<List<TradingDay>> tradingDays(Market market, LocalDate from, LocalDate to) {
        return requireQot(() -> marketData.tradingDays(market, from, to));
    }

    @Override
    public CompletableFuture<Void> subscribeQuotes(List<Instrument> instruments) {
        return requireQot(() -> marketData.subscribeQuotes(instruments));
    }

    @Override
    public CompletableFuture<Void> unsubscribeQuotes(List<Instrument> instruments) {
        return requireQot(() -> marketData.unsubscribeQuotes(instruments));
    }

    @Override
    public CompletableFuture<SubscriptionInfo> subscriptionInfo() {
        return requireQot(marketData::subscriptionInfo);
    }

    @Override
    public void addQuoteListener(QuoteListener listener) {
        quoteListeners.add(listener);
    }

    @Override
    public CompletableFuture<List<ValuationSnapshot>> snapshots(List<Instrument> instruments) {
        if (instruments.size() > SNAPSHOT_BATCH) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "快照一次最多 " + SNAPSHOT_BATCH + " 只，收到 " + instruments.size() + " 只，请分批"));
        }
        return requireQot(() -> marketData.snapshots(instruments));
    }

    @Override
    public CompletableFuture<List<FinancialReport>> financials(Instrument instrument, FinancialStatement statement, int periods) {
        return requireQot(() -> marketData.financials(instrument, statement, periods));
    }

    @Override
    public CompletableFuture<CompanyProfile> companyProfile(Instrument instrument) {
        return requireQot(() -> marketData.companyProfile(instrument));
    }

    /** 当前时段：优先心跳拿到的 marketUS，否则美东时钟。 */
    public MarketSession currentSession() {
        return FutuQuotes.session(stateFacts.get("market.US"), null);
    }

    private void deliverQuotes(List<QotCommon.BasicQot> list) {
        MarketSession session = currentSession();
        Instant now = Instant.now();
        for (QotCommon.BasicQot q : list) {
            Quote quote;
            try {
                quote = FutuQuotes.toQuote(q, session, now);
            } catch (RuntimeException e) {
                log.warn("报价映射失败：{}", e.toString());
                continue;
            }
            for (QuoteListener l : quoteListeners) {
                try {
                    l.onQuote(quote);
                } catch (RuntimeException e) {
                    log.warn("报价监听器抛出异常：{}", e.toString());
                }
            }
        }
    }

    @Override
    public void close() {
        if (!props.enabled()) {
            return;
        }
        try {
            disconnect();
        } catch (RuntimeException e) {
            log.warn("断开富途网关时出错：{}", e.toString());
        }
        dispatch.shutdown();
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
