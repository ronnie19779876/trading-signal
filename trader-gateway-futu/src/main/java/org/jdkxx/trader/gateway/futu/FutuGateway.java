package org.jdkxx.trader.gateway.futu;

import com.futu.openapi.pb.GetGlobalState;
import com.futu.openapi.pb.QotCommon;
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
import org.jdkxx.trader.common.ratelimit.RateLimiter;
import org.jdkxx.trader.common.ratelimit.SlidingWindowRateLimiter;
import org.jdkxx.trader.domain.AccountRef;
import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.domain.CompanyProfile;
import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.FinancialReport;
import org.jdkxx.trader.domain.FinancialStatement;
import org.jdkxx.trader.domain.HistoryQuota;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.InstrumentStatic;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.MarketSession;
import org.jdkxx.trader.domain.Quote;
import org.jdkxx.trader.domain.RehabFactor;
import org.jdkxx.trader.domain.SubscriptionInfo;
import org.jdkxx.trader.domain.TradingDay;
import org.jdkxx.trader.domain.ValuationSnapshot;
import org.jdkxx.trader.domain.selection.EarningsEvent;
import org.jdkxx.trader.domain.selection.ValuationMetric;
import org.jdkxx.trader.domain.selection.ValuationPoint;
import org.jdkxx.trader.domain.selection.ValuationRange;
import org.jdkxx.trader.gateway.BrokerGateway;
import org.jdkxx.trader.gateway.GatewayException;
import org.jdkxx.trader.gateway.GatewayListener;
import org.jdkxx.trader.gateway.GatewayState;
import org.jdkxx.trader.gateway.GatewayStatus;
import org.jdkxx.trader.gateway.MarketDataGateway;
import org.jdkxx.trader.gateway.NotConnectedException;
import org.jdkxx.trader.gateway.QuoteListener;
import org.jdkxx.trader.gateway.SelectionDataGateway;
import org.jdkxx.trader.gateway.futu.mapper.FutuAccounts;
import org.jdkxx.trader.gateway.futu.mapper.FutuQuotes;
import org.jdkxx.trader.gateway.futu.mapper.FutuStates;
import org.jdkxx.trader.gateway.futu.marketdata.FutuMarketData;
import org.jdkxx.trader.gateway.support.ConnectionSupervisor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Public entry point of the Futu gateway adapter. The two channels (market data QOT, trading TRD)
 * each have their own {@link ConnectionSupervisor}, and the gateway-level state is a combination of
 * both: it is CONNECTED only when both are. Listeners receive gateway-level events (not one event
 * per channel).
 *
 * <p>Threads: {@code futu-scheduler} (2 threads) runs both supervisors' state machines, heartbeats
 * and reply timeouts; {@code futu-dispatch} (1 thread) completes every reply future and delivers
 * quote pushes; {@code futu-pager} (1 thread) sends follow-up history pages, because a follow-up
 * request has to wait on the rate limiter and must not do so on dispatch. The SDK's own network
 * thread only hands work over (see {@link FutuChannel}).
 */
public class FutuGateway
    implements BrokerGateway, MarketDataGateway, SelectionDataGateway, AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(FutuGateway.class);

  private final FutuProperties props;
  private final ScheduledExecutorService scheduler;
  private final ExecutorService dispatch;

  /**
   * Sends follow-up pages of historical bars; they must not be sent on dispatch (see {@link
   * FutuChannel#qotCall}).
   */
  private final ExecutorService pager;

  private final FutuChannel qot;
  private final FutuChannel trd;
  private final ConnectionSupervisor qotSupervisor;
  private final ConnectionSupervisor trdSupervisor;
  private final FutuMarketData marketData;
  private final ConcurrentHashMap<String, RateLimiter> limiters = new ConcurrentHashMap<>();
  private final List<GatewayListener> listeners = new CopyOnWriteArrayList<>();
  private final List<QuoteListener> quoteListeners = new CopyOnWriteArrayList<>();
  private volatile Map<String, String> stateFacts = Map.of();

  /**
   * The two channels' listeners may run at the same time on the two scheduler threads, so checking
   * and flipping the gateway-level up/down state must happen under a single lock.
   */
  private final Object upLock = new Object();

  private boolean up;
  private boolean everUp;

  public FutuGateway(FutuProperties props) {
    props.validate();
    this.props = props;
    if (!props.enabled()) {
      scheduler = null;
      dispatch = null;
      pager = null;
      qot = null;
      trd = null;
      qotSupervisor = null;
      trdSupervisor = null;
      marketData = null;
      return;
    }
    scheduler = Executors.newScheduledThreadPool(2, named("futu-scheduler"));
    dispatch = Executors.newSingleThreadExecutor(named(FutuChannel.DISPATCH_THREAD));
    pager = Executors.newSingleThreadExecutor(named("futu-pager"));
    qot =
        new FutuChannel(
            FutuChannel.Kind.QOT,
            props,
            new FutuReplyRegistry("行情", scheduler, dispatch, props.replyTimeout()),
            this::limiter);
    trd =
        new FutuChannel(
            FutuChannel.Kind.TRD,
            props,
            new FutuReplyRegistry("交易", scheduler, dispatch, props.replyTimeout()),
            this::limiter);
    qotSupervisor =
        new ConnectionSupervisor(Broker.FUTU, "富途行情通道", qot, props.supervisorSettings(), scheduler);
    trdSupervisor =
        new ConnectionSupervisor(Broker.FUTU, "富途交易通道", trd, props.supervisorSettings(), scheduler);
    marketData = new FutuMarketData(qot::qotCall, pager);
    qot.onClosed(qotSupervisor::onTransportClosed);
    trd.onClosed(trdSupervisor::onTransportClosed);
    qot.onGlobalState(s -> stateFacts = FutuStates.facts(s));
    // SDK network thread → futu-dispatch: mapping and quote listeners never run on the SDK thread
    qot.onBasicQuote(
        rsp -> dispatch.execute(() -> deliverQuotes(rsp.getS2C().getBasicQotListList())));
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

  /**
   * One sliding-window limiter per request name, shared by both channels. At least 20 ms between
   * calls; a wait longer than 30 s throws instead of blocking indefinitely.
   */
  private RateLimiter limiter(String name) {
    return limiters.computeIfAbsent(
        name,
        n ->
            new SlidingWindowRateLimiter(
                "futu-" + n, props.limit(n), Duration.ofMillis(20), Duration.ofSeconds(30)));
  }

  /** Combines the two channels' events into gateway-level events. */
  private GatewayListener channelListener() {
    return new GatewayListener() {
      @Override
      public void onConnected(Broker broker, boolean reconnected) {
        if (qotSupervisor.isConnected() && qot.isConnected()) {
          // Fetch the global state once on connect so that the facts are available right away
          // instead of after the first heartbeat
          qot.globalState().exceptionally(ex -> null);
        }
        boolean fire = false;
        boolean re = false;
        synchronized (upLock) {
          if (qotSupervisor.isConnected() && trdSupervisor.isConnected() && !up) {
            up = true;
            re = everUp;
            everUp = true;
            fire = true;
          }
        }
        if (fire) {
          boolean reconnectedGateway = re;
          notifyListeners(l -> l.onConnected(Broker.FUTU, reconnectedGateway));
        }
      }

      @Override
      public void onDisconnected(Broker broker, String reason) {
        boolean fire;
        synchronized (upLock) {
          fire = up;
          up = false;
        }
        if (fire) {
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
      detail =
          !ps.isEmpty() && !"Ready".equals(ps)
              ? "已连接，但 OpenD 状态为 " + ps
              : !qotLogined ? "已连接，但 OpenD 行情未登录" : "已连接（行情 + 交易通道）";
    } else {
      detail = "行情通道：" + q.detail() + "；交易通道：" + t.detail();
    }
    Instant since =
        q.connectedSince() == null || t.connectedSince() == null
            ? null
            : (q.connectedSince().isAfter(t.connectedSince())
                ? q.connectedSince()
                : t.connectedSince());
    Instant heartbeat =
        q.lastHeartbeatAt() == null
            ? t.lastHeartbeatAt()
            : t.lastHeartbeatAt() == null
                ? q.lastHeartbeatAt()
                : (q.lastHeartbeatAt().isBefore(t.lastHeartbeatAt())
                    ? q.lastHeartbeatAt()
                    : t.lastHeartbeatAt());
    return new GatewayStatus(
        state,
        detail,
        Instant.now(),
        since,
        heartbeat,
        Math.max(q.reconnectAttempts(), t.reconnectAttempts()),
        facts);
  }

  /**
   * Combined state, in order of precedence: ERROR, both CONNECTED, RECONNECTING, CONNECTING,
   * otherwise DISCONNECTED.
   */
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
      return CompletableFuture.failedFuture(
          new NotConnectedException(Broker.FUTU, status().detail()));
    }
    return trd.accList().thenApply(FutuAccounts::map);
  }

  /** OpenD global state (available once the market-data channel is ready). */
  public CompletableFuture<GetGlobalState.S2C> globalState() {
    if (!props.enabled() || !qotSupervisor.isConnected()) {
      return CompletableFuture.failedFuture(
          new NotConnectedException(Broker.FUTU, status().detail()));
    }
    return qot.globalState();
  }

  @Override
  public void addListener(GatewayListener listener) {
    listeners.add(listener);
  }

  // ------------------------------------------------------------------ MarketDataGateway
  // (market-data channel)

  private <T> CompletableFuture<T> requireQot(
      java.util.function.Supplier<CompletableFuture<T>> call) {
    if (!props.enabled() || !qotSupervisor.isConnected() || !qot.isConnected()) {
      return CompletableFuture.failedFuture(
          new NotConnectedException(Broker.FUTU, status().detail()));
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
  public CompletableFuture<List<DailyBar>> historyDailyBars(
      Instrument instrument, LocalDate from, LocalDate to) {
    return requireQot(() -> marketData.historyDailyBars(instrument, from, to));
  }

  /**
   * Futu's own price-adjusted series (1 forward adjustment / 2 backward adjustment), used only to
   * cross-check the adjustment done in the read layer.
   */
  public CompletableFuture<List<DailyBar>> historyDailyBarsAdjusted(
      Instrument instrument, LocalDate from, LocalDate to, int rehabType) {
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
  public CompletableFuture<List<TradingDay>> tradingDays(
      Market market, LocalDate from, LocalDate to) {
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
      return CompletableFuture.failedFuture(
          new IllegalArgumentException(
              "快照一次最多 " + SNAPSHOT_BATCH + " 只，收到 " + instruments.size() + " 只，请分批"));
    }
    return requireQot(() -> marketData.snapshots(instruments));
  }

  @Override
  public CompletableFuture<List<FinancialReport>> financials(
      Instrument instrument, FinancialStatement statement, int periods) {
    return requireQot(() -> marketData.financials(instrument, statement, periods));
  }

  // ------------------------------------------------------------------ Quant stock selection
  // (§25.6)

  @Override
  public CompletableFuture<List<FinancialReport>> quarterlyFinancials(
      Instrument instrument, FinancialStatement statement, int periods) {
    return requireQot(() -> marketData.quarterlyFinancials(instrument, statement, periods));
  }

  @Override
  public CompletableFuture<List<ValuationPoint>> valuationHistory(
      Instrument instrument, ValuationMetric metric, ValuationRange range) {
    return requireQot(() -> marketData.valuationHistory(instrument, metric, range));
  }

  @Override
  public CompletableFuture<List<EarningsEvent>> earningsHistory(Instrument instrument) {
    return requireQot(() -> marketData.earningsHistory(instrument));
  }

  @Override
  public CompletableFuture<CompanyProfile> companyProfile(Instrument instrument) {
    return requireQot(() -> marketData.companyProfile(instrument));
  }

  /**
   * Current session: preferably from the marketUS state obtained by the heartbeat, otherwise from
   * the US/Eastern clock. The heartbeat runs every 30 s, so around a session boundary the answer
   * may lag by up to that long.
   */
  public MarketSession currentSession() {
    return FutuQuotes.session(stateFacts.get("market.US"), null);
  }

  /**
   * Runs on futu-dispatch. A mapping failure or a throwing listener affects only that one quote or
   * listener, never the rest of the batch. Listeners must not block: this thread also completes
   * every reply.
   */
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
    pager.shutdownNow();
    dispatch.shutdown();
    scheduler.shutdown();
    try {
      scheduler.awaitTermination(2, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
