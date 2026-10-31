package org.jdkxx.trader.gateway.ibkr;

import com.ib.client.Contract;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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

/**
 * Public entry point of the IBKR gateway adapter: the lifecycle is driven by {@link
 * ConnectionSupervisor}, the session is owned by {@link IbkrConnection}. Capabilities: connect /
 * reconnect / heartbeat, managed accounts, contract lookup; positions and account summary
 * (read-only) since phase 3; live-account subscriptions since 3.0.2.
 *
 * <p>Threads: {@code ibkr-scheduler} (2 threads) runs the supervisor's state machine, its listeners
 * and every timer; {@code ibkr-dispatch} (1 thread) completes futures and owns the standing
 * subscribers ({@link IbkrAccountSummaryFeed}, {@link IbkrPortfolioFeed}, {@link IbkrLiveAccount}),
 * which is why those fields are touched only from dispatch tasks. Public methods may be called from
 * any thread and only hand work over.
 */
public class IbkrGateway
    implements BrokerGateway,
        ReferenceDataGateway,
        AccountGateway,
        LiveAccountGateway,
        PriceHistoryGateway,
        AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(IbkrGateway.class);

  private final IbkrProperties props;
  private final ScheduledExecutorService scheduler;
  private final ExecutorService dispatch;
  private final IbkrFacts facts = new IbkrFacts();
  private final IbkrConnection connection;
  private final IbkrSubscriptions subscriptions;
  private final ConnectionSupervisor supervisor;

  /**
   * The standing account-summary subscription: created with the connection, released on disconnect,
   * shared by the live account and the daily snapshot (3.0.9). Read and written only on the
   * dispatch thread.
   */
  private IbkrAccountSummaryFeed summaryFeed;

  /**
   * Historical-data pacing (quantitative selection, §25.6): IBKR allows at most 60 requests per 10
   * minutes, we use 50 to keep a margin. The wait happens on the caller's thread (a job thread, at
   * most a little over one window), never on the pump or dispatch thread.
   */
  private final RateLimiter historicalPacing =
      new SlidingWindowRateLimiter(
          "ibkr-historical",
          RateLimitSpec.parse("50/10m"),
          Duration.ofSeconds(1),
          Duration.ofMinutes(11));

  /**
   * Many years of daily bars in one request is slower than an ordinary request, so it gets its own
   * timeout.
   */
  static final Duration HISTORICAL_TIMEOUT = Duration.ofSeconds(120);

  /** Earliest data time measured at 0.4 to 0.7 seconds (SKYD, CRH, SW); 30 seconds is plenty. */
  static final Duration HEAD_TIMESTAMP_TIMEOUT = Duration.ofSeconds(30);

  private static final DateTimeFormatter TWS_DAY = DateTimeFormatter.BASIC_ISO_DATE;

  /** Live-account subscription: read and written only on the dispatch thread. */
  private IbkrLiveAccount live;

  private volatile boolean liveIntent;

  /**
   * Account-updates feed (3.4.6): position average cost and unrealized P&amp;L from the same source
   * as the IBKR app. <b>Enabled on the production instance only</b>: only one API client may
   * subscribe to an account at a time, so a dev instance subscribing would kick production off.
   * Created, subscribed and unsubscribed on the dispatch thread; the field is volatile so the
   * snapshot's position query can read it.
   */
  private final boolean portfolioFeedEnabled;

  private volatile IbkrPortfolioFeed portfolioFeed;

  public IbkrGateway(IbkrProperties props) {
    this(props, false);
  }

  /**
   * @param portfolioFeedEnabled whether to subscribe to the account-updates feed (production
   *     instance only: {@code trader.environment=PROD})
   */
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
    IbkrRequestRegistry registry =
        new IbkrRequestRegistry(scheduler, dispatch, props.requestTimeout());
    subscriptions = new IbkrSubscriptions(dispatch);
    connection =
        new IbkrConnection(
            props,
            registry,
            subscriptions,
            facts,
            dispatch,
            new TokenBucketRateLimiter(
                "ibkr-messages",
                props.messageRatePerSecond(),
                props.messageRatePerSecond(),
                Duration.ofSeconds(30)));
    supervisor =
        new ConnectionSupervisor(
            Broker.IBKR, "盈透网关", connection, props.supervisorSettings(), scheduler);
    connection.onClosed(supervisor::onTransportClosed);
    connection.onDataLost(supervisor::notifyDataLost);
    supervisor.addListener(
        new GatewayListener() {
          @Override
          public void onConnected(Broker broker, boolean reconnected) {
            verifyConfiguredAccount();
            // The first connect, a reconnect after a drop, and a 1101 data loss all arrive here (on
            // the scheduler thread)
            dispatch.execute(
                () -> {
                  // The account summary is standing: subscribed once per connection cycle. But on
                  // 1101 (data lost) the session stays the same while the broker has dropped the
                  // subscription, so it must be re-subscribed explicitly; both cases where
                  // reconnected is true are passed through, see subscribe(boolean)
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
            dispatch.execute(
                () -> {
                  if (summaryFeed != null) {
                    summaryFeed
                        // pending snapshot requests fail right away instead of waiting out the 90
                        // seconds
                        .stop();
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
   * When trader.ibkr.account is configured it must be in the managed-account list; otherwise it is
   * a non-retryable configuration error. If the list has not arrived yet it is requested
   * explicitly: before 2.0.2 an empty list was simply let through and never checked again. Error
   * messages deliberately never include the account number.
   */
  private void verifyConfiguredAccount() {
    if (props.account() == null || props.account().isBlank()) {
      return;
    }
    String wanted = props.account().trim();
    connection
        .managedAccounts()
        .orTimeout(props.requestTimeout().toMillis(), TimeUnit.MILLISECONDS)
        .whenCompleteAsync(
            (accounts, ex) -> {
              if (ex != null) {
                // Not getting the list must not count as a pass: drop and reconnect, then check
                // again once connected
                supervisor.onTransportClosed("核对受管账户列表失败：" + causeMessage(ex));
              } else if (accounts.isEmpty()) {
                supervisor.reportFatal(
                    new GatewayException(
                        Broker.IBKR, 0, "网关没有返回受管账户列表，无法核对 trader.ibkr.account，拒绝继续", false));
              } else if (!accounts.contains(wanted)) {
                supervisor.reportFatal(
                    new GatewayException(
                        Broker.IBKR,
                        0,
                        "配置的 trader.ibkr.account 不在网关的受管账户列表里，拒绝继续（请核对账户号）",
                        false));
              }
            },
            scheduler);
  }

  private static String causeMessage(Throwable t) {
    Throwable c = t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
    return c.getMessage() == null || c.getMessage().isBlank()
        ? c.getClass().getSimpleName()
        : c.getMessage();
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
      return CompletableFuture.failedFuture(
          new NotConnectedException(Broker.IBKR, status().detail()));
    }
    return connection
        .managedAccounts()
        .thenApply(
            ids ->
                ids.stream()
                    .map(
                        id ->
                            new AccountRef(
                                Broker.IBKR,
                                id,
                                id.startsWith("DU") ? AccountKind.PAPER : AccountKind.LIVE,
                                Set.of(Market.US)))
                    .toList());
  }

  /**
   * The gateway's current time (reqCurrentTime), useful for checking clock skew between this host
   * and the broker.
   */
  public CompletableFuture<java.time.Instant> serverTime() {
    if (!isConnected()) {
      return CompletableFuture.failedFuture(
          new NotConnectedException(Broker.IBKR, status().detail()));
    }
    return connection.currentTime();
  }

  @Override
  public CompletableFuture<List<InstrumentInfo>> lookup(Instrument instrument) {
    if (!isConnected()) {
      return CompletableFuture.failedFuture(
          new NotConnectedException(Broker.IBKR, status().detail()));
    }
    return connection
        .contractDetails(IbkrContracts.stock(instrument, props.primaryExchange()))
        .thenApply(list -> list.stream().map(cd -> IbkrInstruments.toInfo(instrument, cd)).toList())
        .exceptionally(
            ex -> {
              Throwable c =
                  ex instanceof CompletionException && ex.getCause() != null ? ex.getCause() : ex;
              if (c instanceof RequestRejectedException r && r.code() == 200) {
                return List.of(); // 200 = no security definition, i.e. no such instrument
              }
              throw c instanceof RuntimeException re ? re : new CompletionException(c);
            });
  }

  @Override
  public CompletableFuture<List<SplitAdjustedBar>> splitAdjustedDailyBars(
      long conId, LocalDate from, LocalDate to) {
    if (from.isAfter(to)) {
      return CompletableFuture.failedFuture(
          new IllegalArgumentException("from 不能晚于 to：" + from + " > " + to));
    }
    if (!isConnected()) {
      return CompletableFuture.failedFuture(
          new NotConnectedException(Broker.IBKR, status().detail()));
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
        .thenCompose(
            contract -> connection.historicalDailyBars(contract, end, duration, HISTORICAL_TIMEOUT))
        .thenApply(bars -> IbkrBars.toSplitAdjusted(bars, from, to));
  }

  /**
   * IBKR counts earliest-data-time requests against the historical pacing (60 per 10 minutes), so
   * they share the limiter with daily bars.
   */
  @Override
  public CompletableFuture<LocalDate> earliestDailyBar(long conId) {
    if (!isConnected()) {
      return CompletableFuture.failedFuture(
          new NotConnectedException(Broker.IBKR, status().detail()));
    }
    try {
      historicalPacing.acquire();
    } catch (RateLimitExceededException e) {
      return CompletableFuture.failedFuture(e);
    }
    return historicalContract(conId)
        .thenCompose(contract -> connection.headTimestamp(contract, HEAD_TIMESTAMP_TIMEOUT))
        .thenApply(
            ts -> {
              LocalDate day = IbkrBars.headDay(ts);
              if (day == null) {
                throw new IllegalStateException("盈透最早数据时间解析不了：" + ts);
              }
              return day;
            });
  }

  /**
   * Contract for historical requests: by primary exchange, not SMART. SMART only returns history
   * after the most recent exchange transfer (measured 2026-10-11: PEP moved from NYSE to NASDAQ on
   * 2017-12-20; SMART gave only 132 bars, NASDAQ gave 755 bars from 2015 spanning the transfer
   * date; WDAY and COO likewise). For KO and AAPL both ways return identical bars.
   */
  private CompletableFuture<Contract> historicalContract(long conId) {
    return connection
        .contractDetails(IbkrContracts.byConId(conId))
        .thenApply(
            details -> {
              String primary =
                  details.isEmpty() || details.getFirst().contract() == null
                      ? null
                      : details.getFirst().contract().primaryExch();
              return IbkrContracts.historical(conId, primary);
            });
  }

  @Override
  public CompletableFuture<List<Position>> positions(String accountId) {
    requireAccountId(accountId);
    if (!isConnected()) {
      return CompletableFuture.failedFuture(
          new NotConnectedException(Broker.IBKR, status().detail()));
    }
    return connection
        .positions(accountId)
        .thenApply(rows -> withPortfolioCost(IbkrAccounts.positions(accountId, rows)));
  }

  /**
   * When the daily snapshot fetches positions, the average cost likewise prefers the
   * account-updates feed (when the quantities match): on 2026-10-20, the day of an add, the
   * snapshot stored the old average cost. Returned unchanged when the feed is not subscribed (dev
   * instance) or not complete yet. Runs on the dispatch thread (the positions future completes
   * there).
   */
  private List<Position> withPortfolioCost(List<Position> list) {
    IbkrPortfolioFeed pf = portfolioFeed;
    java.util.Map<String, IbkrAccounts.PortfolioRow> rows =
        pf == null ? java.util.Map.of() : pf.rows();
    if (rows.isEmpty()) {
      return list;
    }
    List<Position> out = new java.util.ArrayList<>(list.size());
    for (Position p : list) {
      java.math.BigDecimal cost =
          IbkrPortfolioFeed.cost(
              rows, p.brokerRef(), p.quantity(), p.averageCost(), p.symbol(), pf.costDiffLogged());
      out.add(
          cost == null
              ? p
              : new Position(
                  p.broker(),
                  p.accountId(),
                  p.brokerRef(),
                  p.symbol(),
                  p.localSymbol(),
                  p.securityType(),
                  p.exchange(),
                  p.primaryExchange(),
                  p.currency(),
                  p.quantity(),
                  cost));
    }
    return List.copyOf(out);
  }

  /**
   * Which account the account-updates feed subscribes to: the configured one first; if none is
   * configured and the gateway reported exactly one managed account, that one; otherwise
   * undecidable (returns null).
   */
  private String portfolioAccount() {
    if (props.account() != null && !props.account().isBlank()) {
      return props.account().trim();
    }
    List<String> managed = facts.managedAccounts();
    return managed.size() == 1 ? managed.get(0) : null;
  }

  /**
   * The account-updates feed, created lazily; null when not enabled. Called only on the dispatch
   * thread.
   */
  private IbkrPortfolioFeed portfolioFeed() {
    if (!portfolioFeedEnabled) {
      return null;
    }
    if (portfolioFeed == null) {
      IbkrPortfolioFeed pf = new IbkrPortfolioFeed(new LiveWire(), this::portfolioAccount);
      pf.onChange(
          () -> {
            if (live != null) {
              live.onPortfolioChanged();
            }
          });
      portfolioFeed = pf;
    }
    return portfolioFeed;
  }

  /**
   * Account summary: reads the latest values of the <b>standing subscription</b> (changed in 3.0.9)
   * instead of sending its own {@code reqAccountSummary} every time.
   *
   * <p>IBKR's limit of 2 per client counts subscriptions ever made, and cancelling does not free a
   * slot (measured in production on 2026-09-23, see {@link IbkrAccountSummaryFeed}), so "subscribe,
   * use, cancel" does not work: the snapshot job subscribing once a day would sooner or later
   * exhaust the slots, after which not even the live account could subscribe. Now there is exactly
   * one subscription per connection cycle, shared by both.
   *
   * <p>Cost: the result is the most recent push (the broker sends a batch roughly every 3 minutes),
   * not a fresh query. Before the first batch has arrived it waits at most {@link
   * IbkrAccountSummaryFeed#WAIT}; on timeout it fails and the caller handles it (the snapshot job
   * records FAILED and the 21:00 catch-up retries).
   */
  @Override
  public CompletableFuture<AccountSummary> accountSummary(String accountId) {
    requireAccountId(accountId);
    if (!isConnected()) {
      return CompletableFuture.failedFuture(
          new NotConnectedException(Broker.IBKR, status().detail()));
    }
    CompletableFuture<AccountSummary> out = new CompletableFuture<>();
    dispatch.execute(() -> summaryFeed().request(accountId, out));
    return out;
  }

  /** The standing summary subscription, created lazily; called only on the dispatch thread. */
  private IbkrAccountSummaryFeed summaryFeed() {
    if (summaryFeed == null) {
      summaryFeed = new IbkrAccountSummaryFeed(new LiveWire());
    }
    return summaryFeed;
  }

  // ------------------------------------------------------------------ live account

  @Override
  public void startLive(String accountId, LiveAccountListener listener) {
    requireAccountId(accountId);
    if (!props.enabled()) {
      return;
    }
    liveIntent = true;
    dispatch.execute(
        () -> {
          summaryFeed()
              .listener(
                  accountId,
                  // funds come from the standing subscription; existing data is delivered
                  // immediately
                  listener);
          summaryFeed().subscribe();
          IbkrPortfolioFeed pf = portfolioFeed();
          if (pf != null && isConnected() && !pf.subscribed()) {
            pf.subscribe(
                false,
                // account undecidable at connect time (not configured, managed list not in yet):
                // subscribe with the live account's account
                accountId);
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
    dispatch.execute(
        () -> {
          if (summaryFeed != null) {
            summaryFeed.listener(
                null,
                // only detach the listener; keep the standing subscription, cancelling would not
                // give the slot back
                null);
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

  /**
   * Seam between the subscribers and the connection. {@link #later} runs its task on the dispatch
   * thread (the scheduler only provides the timing), keeping every subscriber callback on dispatch.
   */
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
    public boolean subscribe(
        String what, java.util.function.Consumer<com.ib.client.EClientSocket> request) {
      return connection.subscribe(what, request);
    }

    @Override
    public void unsubscribe(
        String what, java.util.function.Consumer<com.ib.client.EClientSocket> cancel) {
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
      java.util.concurrent.ScheduledFuture<?> f =
          scheduler.schedule(() -> dispatch.execute(task), delay.toMillis(), TimeUnit.MILLISECONDS);
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
