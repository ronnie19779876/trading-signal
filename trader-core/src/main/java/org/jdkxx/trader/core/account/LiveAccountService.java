package org.jdkxx.trader.core.account;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.jdkxx.trader.domain.AccountPnl;
import org.jdkxx.trader.domain.AccountRef;
import org.jdkxx.trader.domain.AccountSummary;
import org.jdkxx.trader.domain.Position;
import org.jdkxx.trader.domain.PositionPnl;
import org.jdkxx.trader.domain.PositionPrice;
import org.jdkxx.trader.gateway.BrokerGateway;
import org.jdkxx.trader.gateway.GatewayException;
import org.jdkxx.trader.gateway.GatewayState;
import org.jdkxx.trader.gateway.LiveAccountGateway;
import org.jdkxx.trader.gateway.LiveAccountListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Real-time account (since 3.0.2): subscribes on demand to IBKR funds, P&amp;L, positions and
 * quotes of the held instruments, for the dashboard. In memory only: not stored, not part of
 * snapshots.
 *
 * <p><b>Matches the IBKR app</b> (user requirement of 2026-09-19, checked item by item against app
 * screenshots): net liquidation, cash etc. come from the account summary (pushed about every 3
 * minutes); daily / unrealized / realized P&amp;L from the account P&amp;L; per-position market
 * value and P&amp;L from the per-position P&amp;L; last price and prior close from market data.
 * This system's own estimates never replace IBKR's numbers. Three more values in the app are
 * computed by the app itself from IBKR raw values, and are given here the same way: change (last -
 * prior close) and change %, cost basis (average cost x quantity), and % of portfolio (market value
 * / net liquidation). Against the screenshot, GOOG showed +2.40 / +0.70%, 21,502 and 4.20%, all
 * equal. Amounts and percentages are kept to two decimals (IBKR pushes doubles with floating-point
 * tails). Validating the account P&amp;L (§20, §23, §20.9) happens in the IBKR adapter, not here;
 * this class displays what the adapter delivers.
 *
 * <p><b>On demand</b>: it subscribes only when someone reads, and unsubscribes after {@value
 * #IDLE_MINUTES} minutes without readers. The same gateway also serves the system that really
 * places orders, so it should hold as little as possible. During the first seconds after
 * subscribing the data arrives piece by piece and the status is WARMING.
 *
 * <p>Thread discipline: listener callbacks arrive on the gateway's dispatch thread and only write
 * volatile fields / concurrent maps; reads happen on request threads. A view reads each volatile
 * once into a local, so one response is built from one consistent reference per part (the parts
 * themselves may come from different pushes).
 */
public class LiveAccountService implements LiveAccountListener, AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(LiveAccountService.class);
  static final long IDLE_MINUTES = 5;

  public enum Status {
    /** Data is being pushed. */
    LIVE,
    /** Just subscribed, data not complete yet. */
    WARMING,
    /** Gateway not connected: the last data received is kept, each part with its own time. */
    DISCONNECTED,
    /** Unavailable (gateway disabled, no account could be chosen, etc.); detail explains why. */
    UNAVAILABLE
  }

  /** Funds (IBKR account summary raw values, pushed about every 3 minutes). */
  public record Money(
      BigDecimal netLiquidation,
      BigDecimal totalCash,
      BigDecimal availableFunds,
      BigDecimal buyingPower,
      BigDecimal excessLiquidity,
      BigDecimal grossPositionValue,
      BigDecimal stockMarketValue,
      BigDecimal accruedDividend,
      BigDecimal accruedInterest,
      Instant updatedAt) {}

  /** P&amp;L (IBKR account P&amp;L raw values). */
  public record Pnl(
      BigDecimal daily, BigDecimal unrealized, BigDecimal realized, Instant updatedAt) {}

  /**
   * One position row. IBKR raw values: quantity and average cost from positions; market value /
   * daily / unrealized from the per-position P&amp;L (updatedAt); last price and prior close from
   * market data (lastAt; lastDelayed means it fell back to delayed data). Derived values on the
   * app's basis: change / changePct (last - prior close), costBasis (average cost x quantity),
   * portfolioPct (market value / net liquidation x 100); null when any input is missing.
   */
  public record LivePosition(
      String symbol,
      String conId,
      String securityType,
      String currency,
      BigDecimal quantity,
      BigDecimal averageCost,
      BigDecimal costBasis,
      BigDecimal last,
      BigDecimal priorClose,
      BigDecimal change,
      BigDecimal changePct,
      Instant lastAt,
      boolean lastDelayed,
      BigDecimal marketValue,
      BigDecimal dailyPnl,
      BigDecimal unrealizedPnl,
      BigDecimal portfolioPct,
      boolean cashEquivalent,
      Instant updatedAt) {}

  public record LiveView(
      Status status,
      String detail,
      String accountMask,
      String currency,
      Instant startedAt,
      Money money,
      Pnl pnl,
      List<LivePosition> positions,
      Instant positionsUpdatedAt,
      String lastError) {}

  private final AccountProperties props;
  private final String configuredAccount;
  private final BrokerGateway broker;
  private final LiveAccountGateway live;
  private final Clock clock;
  private final ScheduledExecutorService idle;

  private volatile String accountId;
  private volatile Instant lastAccess = Instant.EPOCH;
  private volatile Instant startedAt;
  private volatile AccountSummary summary;
  private volatile AccountPnl pnl;
  private volatile List<Position> positions;
  private volatile Instant positionsAt;
  private final Map<String, PositionPnl> singles = new ConcurrentHashMap<>();
  private final Map<String, PositionPrice> prices = new ConcurrentHashMap<>();
  private volatile String lastError;

  /**
   * Which feed lastError belongs to (the {@code what} label, e.g. the account-summary feed): a new
   * push on that feed means it recovered, so the error is cleared.
   */
  private volatile String lastErrorWhat;

  public LiveAccountService(
      AccountProperties props,
      String configuredAccount,
      BrokerGateway broker,
      LiveAccountGateway live,
      Clock clock) {
    this.props = props;
    this.configuredAccount = configuredAccount;
    this.broker = broker;
    this.live = live;
    this.clock = clock;
    this.idle =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "live-account-idle");
              t.setDaemon(true);
              return t;
            });
    idle.scheduleWithFixedDelay(this::stopIfIdle, 30, 30, TimeUnit.SECONDS);
  }

  /**
   * Reads the real-time account once. The first read (or the first after an idle unsubscribe)
   * starts the subscription and returns WARMING. Every read refreshes the idle timer. Two
   * concurrent first reads may both call {@code startLive}; the adapter handles that idempotently
   * on its dispatch thread.
   */
  public LiveView view() {
    lastAccess = clock.instant();
    if (!broker.enabled()) {
      return unavailable("盈透网关未启用（trader.ibkr.enabled=false）");
    }
    if (!live.liveActive()) {
      String reason = start();
      if (reason != null) {
        return unavailable(reason);
      }
    }
    return snapshotView();
  }

  /**
   * @return the failure reason; null on success
   */
  private String start() {
    if (broker.status().state() != GatewayState.CONNECTED) {
      return "盈透网关未连接：" + broker.status().detail();
    }
    try {
      List<String> ids =
          broker.accounts().get(10, TimeUnit.SECONDS).stream().map(AccountRef::accountId).toList();
      String chosen = AccountPositions.choose(configuredAccount, ids);
      // Drop whatever an earlier subscription left behind before the new one starts pushing.
      clear();
      accountId = chosen;
      startedAt = clock.instant();
      live.startLive(chosen, this);
      log.info("实时账户：开始订阅（{}）", AccountKeys.mask(chosen));
      return null;
    } catch (Exception e) {
      Throwable c =
          e.getCause() != null && e instanceof java.util.concurrent.ExecutionException
              ? e.getCause()
              : e;
      return "选不出盈透账户：" + (c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage());
    }
  }

  /**
   * Runs every 30 seconds on the {@code live-account-idle} thread. Stopping cancels the adapter's
   * live subscriptions (P&amp;L, positions, quotes) and detaches the listener; the resident
   * account-summary subscription stays, because cancelling it would not give the slot back (§20.7).
   */
  void stopIfIdle() {
    try {
      if (live.liveActive()
          && Duration.between(lastAccess, clock.instant()).toMinutes() >= IDLE_MINUTES) {
        live.stopLive();
        clear();
        log.info("实时账户：{} 分钟没人读，已退订", IDLE_MINUTES);
      }
    } catch (RuntimeException e) {
      log.warn("实时账户闲置检查出错：{}", e.toString());
    }
  }

  private void clear() {
    summary = null;
    pnl = null;
    positions = null;
    positionsAt = null;
    singles.clear();
    prices.clear();
    lastError = null;
    lastErrorWhat = null;
  }

  private LiveView unavailable(String reason) {
    return new LiveView(
        Status.UNAVAILABLE, reason, null, null, null, null, null, List.of(), null, null);
  }

  LiveView snapshotView() {
    AccountSummary s = summary;
    List<Position> ps = positions;
    AccountPnl p = pnl;
    boolean connected = broker.status().state() == GatewayState.CONNECTED;
    Status status =
        !connected ? Status.DISCONNECTED : (s == null || ps == null) ? Status.WARMING : Status.LIVE;
    String detail =
        switch (status) {
          case DISCONNECTED -> "盈透网关未连接：" + broker.status().detail() + "；下面是断线前最后收到的数据";
          case WARMING -> "刚订阅，数据陆续到齐（通常 2 秒内）";
          default -> null;
        };
    List<LivePosition> rows =
        ps == null ? List.of() : positionRows(ps, s == null ? null : s.netLiquidation());
    return new LiveView(
        status,
        detail,
        accountId == null ? null : AccountKeys.mask(accountId),
        s == null ? null : s.currency(),
        startedAt,
        money(s),
        pnl(p),
        rows,
        positionsAt,
        lastError);
  }

  private List<LivePosition> positionRows(List<Position> ps, BigDecimal netLiquidation) {
    Set<String> cash =
        props.cashEquivalents().stream()
            .map(AccountPositions::normalize)
            .collect(Collectors.toSet());
    List<LivePosition> out = new ArrayList<>();
    for (Position q : ps) {
      PositionPnl v = singles.get(q.brokerRef());
      PositionPrice px = prices.get(q.brokerRef());
      String symbol = AccountPositions.normalize(q.symbol());
      BigDecimal last = px == null ? null : px.last();
      BigDecimal prior = px == null ? null : px.priorClose();
      BigDecimal value = v == null ? null : cents(v.marketValue());
      BigDecimal change = last == null || prior == null ? null : last.subtract(prior);
      out.add(
          new LivePosition(
              symbol,
              q.brokerRef(),
              q.securityType(),
              q.currency(),
              q.quantity(),
              q.averageCost(),
              q.averageCost() == null ? null : cents(q.averageCost().multiply(q.quantity())),
              last,
              prior,
              cents(change),
              change == null ? null : percent(change, prior),
              px == null ? null : px.receivedAt(),
              px != null && px.delayed(),
              value,
              v == null ? null : cents(v.daily()),
              v == null ? null : cents(v.unrealized()),
              value == null || netLiquidation == null ? null : percent(value, netLiquidation),
              cash.contains(symbol),
              v == null ? null : v.receivedAt()));
    }
    return out;
  }

  /** part / whole x 100, two decimals HALF_UP; null when the denominator is 0. */
  static BigDecimal percent(BigDecimal part, BigDecimal whole) {
    return whole.signum() == 0
        ? null
        : part.multiply(BigDecimal.valueOf(100)).divide(whole, 2, RoundingMode.HALF_UP);
  }

  /**
   * IBKR pushes doubles, which carry floating-point tails when converted to BigDecimal
   * (238007.7728881836); amounts keep only cents. This is formatting, not calculation.
   */
  static BigDecimal cents(BigDecimal v) {
    return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
  }

  private static Money money(AccountSummary s) {
    return s == null
        ? null
        : new Money(
            s.netLiquidation(),
            s.totalCash(),
            s.availableFunds(),
            s.buyingPower(),
            s.excessLiquidity(),
            s.grossPositionValue(),
            s.stockMarketValue(),
            s.accruedDividend(),
            s.accruedInterest(),
            s.receivedAt());
  }

  /**
   * Only IBKR's account P&amp;L raw values; null until a valid push arrives (first push discarded,
   * gateway re-subscribing), and the page then shows "waiting for IBKR".
   */
  static Pnl pnl(AccountPnl p) {
    return p == null
        ? null
        : new Pnl(cents(p.daily()), cents(p.unrealized()), cents(p.realized()), p.receivedAt());
  }

  // ------------------------------------------------------------------ pushes (gateway dispatch
  // thread)

  @Override
  public void onSummary(AccountSummary s) {
    summary = s;
    recovered("实时账户汇总");
  }

  @Override
  public void onPnl(AccountPnl p) {
    pnl = p;
    recovered("实时账户盈亏");
  }

  @Override
  public void onPositions(List<Position> list) {
    recovered("实时持仓");
    positions = List.copyOf(list);
    positionsAt = clock.instant();
    Set<String> refs = list.stream().map(Position::brokerRef).collect(Collectors.toSet());
    singles.keySet().retainAll(refs); // positions that were closed are no longer shown
    prices.keySet().retainAll(refs);
  }

  @Override
  public void onPositionPnl(PositionPnl p) {
    singles.put(p.brokerRef(), p);
    recovered("逐只盈亏");
  }

  @Override
  public void onPositionPrice(PositionPrice p) {
    prices.put(p.brokerRef(), p);
    recovered("持仓行情");
  }

  @Override
  public void onLiveError(String what, GatewayException error) {
    lastError = what + "：" + error.getMessage();
    lastErrorWhat = what;
    log.warn("实时账户 {} 被拒：{}", what, error.getMessage());
  }

  /**
   * The feed that failed is pushing again: clear the error so the page does not keep showing a
   * stale one. Up to and including 3.0.7 it was not cleared, and after the 2026-09-21 disconnect
   * and reconnect the page kept showing that 322.
   */
  private void recovered(String what) {
    if (what.equals(lastErrorWhat)) {
      lastErrorWhat = null;
      lastError = null;
    }
  }

  @Override
  public void close() {
    idle.shutdownNow();
    if (live.liveActive()) {
      live.stopLive();
    }
  }
}
