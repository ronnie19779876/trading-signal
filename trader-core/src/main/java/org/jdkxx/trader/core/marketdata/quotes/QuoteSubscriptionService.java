package org.jdkxx.trader.core.marketdata.quotes;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.jdkxx.trader.common.ratelimit.Sleeper;
import org.jdkxx.trader.core.marketdata.MarketDataProperties;
import org.jdkxx.trader.core.marketdata.bars.RotationRefresher;
import org.jdkxx.trader.core.marketdata.universe.UniverseScope;
import org.jdkxx.trader.core.marketdata.universe.UnknownSymbolGuard;
import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.SubscriptionInfo;
import org.jdkxx.trader.gateway.GatewayListener;
import org.jdkxx.trader.gateway.MarketDataGateway;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reconciliation of real-time subscriptions: desired set = pool ∪ holdings; diff it against the
 * subscribed set, subscribe what is new and unsubscribe what is extra (deferring those younger than
 * 1 minute, since Futu refuses to unsubscribe earlier). Triggers: gateway connected
 * (auto-subscribe), reconnect, pool add / remove, manual. During a rotation it can pause (freeing
 * quota) and resume afterwards. On disconnect the subscribed set is cleared (the broker-side
 * subscriptions vanish with the connection).
 *
 * <p>Dev vs production: the subscription quota belongs to the account, so the dev instance
 * (auto-subscribe=false) must not subscribe on its own. {@link #onPoolChanged} and {@link
 * #afterRefresh} enforce that; note that {@link #onConnected} with {@code reconnected=true}
 * reconciles regardless of auto-subscribe.
 *
 * <p>Locking: {@code subscribed}, {@code pausedByRefresh} and {@code resubscribeAfterRefresh} are
 * guarded by the object monitor. {@link #reconcile}, {@link #pause} and {@link #resume} hold the
 * monitor while they block on gateway futures (up to 30 s each) and, in {@code pause(true)}, while
 * sleeping until the youngest subscription is old enough. Anything else that takes the monitor (the
 * connect / disconnect callbacks, {@link #status}) waits for that long.
 */
public class QuoteSubscriptionService
    implements GatewayListener, RotationRefresher.QuotaCoordinator {

  private static final Logger log = LoggerFactory.getLogger(QuoteSubscriptionService.class);
  private static final int SUB_CHUNK = 100;

  public record Result(
      int desired, int subscribed, int added, int removed, int deferred, String error) {}

  public record Status(
      boolean enabled,
      boolean paused,
      int desired,
      int subscribed,
      int deferredUnsubscribe,
      SubscriptionInfo quota,
      Instant lastReconcileAt,
      String lastError) {}

  private final MarketDataProperties.Realtime props;
  private final MarketDataGateway gateway;
  private final UniverseScope scope;
  private final QuoteCache cache;
  private final UnknownSymbolGuard unknownSymbols;
  private final Clock clock;
  private final Sleeper sleeper;
  private final Map<Instrument, Instant> subscribed = new LinkedHashMap<>();
  private volatile boolean paused;

  /** The current pause was started by a rotation (a manual pause is not lifted by the rotation). */
  private boolean pausedByRefresh;

  /**
   * Whether to re-subscribe after the rotation: on an auto-subscribe instance, or when there were
   * subscriptions before the rotation.
   */
  private boolean resubscribeAfterRefresh;

  private volatile Instant lastReconcileAt;
  private volatile String lastError;
  private volatile SubscriptionInfo lastQuota;

  /**
   * The reconciliation's own thread.
   *
   * <p>{@link GatewayListener} callbacks run on the Futu gateway's {@code futu-scheduler} ({@code
   * ConnectionSupervisor.dispatch → scheduler.execute}). That pool has only 2 threads, which also
   * run both channels' heartbeats, connect timeouts, reconnect tasks and every reply-timeout task
   * of the two {@code FutuReplyRegistry} instances. {@link #reconcile()} blocks serially on {@code
   * .get(30, SECONDS)}, so one reconciliation could hold a thread for tens of seconds, and a
   * heartbeat that cannot get scheduled is judged a disconnect (found in the 2026-09-25
   * full-project review). So connect / reconnect only clears state on the listener thread and hands
   * the real reconciliation to this thread. Single-threaded: reconciliations from several
   * reconnects simply queue and never run concurrently.
   *
   * <p>The clearing itself still takes the object monitor on the listener thread, so it waits for
   * any reconciliation or pause that currently holds the monitor (see the class comment).
   */
  private final java.util.concurrent.ExecutorService worker =
      java.util.concurrent.Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "quote-reconcile");
            t.setDaemon(true);
            return t;
          });

  public QuoteSubscriptionService(
      MarketDataProperties.Realtime props,
      MarketDataGateway gateway,
      UniverseScope scope,
      QuoteCache cache,
      UnknownSymbolGuard unknownSymbols,
      Clock clock) {
    this(props, gateway, scope, cache, unknownSymbols, clock, Sleeper.REAL);
  }

  public QuoteSubscriptionService(
      MarketDataProperties.Realtime props,
      MarketDataGateway gateway,
      UniverseScope scope,
      QuoteCache cache,
      UnknownSymbolGuard unknownSymbols,
      Clock clock,
      Sleeper sleeper) {
    this.props = props;
    this.gateway = gateway;
    this.scope = scope;
    this.cache = cache;
    this.unknownSymbols = unknownSymbols;
    this.clock = clock;
    this.sleeper = sleeper;
  }

  /**
   * Instruments that should be subscribed (pool and holdings that Futu has resolved and that are
   * not delisted).
   */
  public Set<Instrument> desired() {
    Set<Instrument> out = new LinkedHashSet<>();
    for (InstrumentRow r : scope.poolAndHoldings()) {
      out.add(r.instrument());
    }
    return out;
  }

  /**
   * Diffs desired against subscribed and applies the difference: subscribe the missing ones in
   * chunks of 100, and unsubscribe the unwanted ones that are at least {@code unsubscribe-min-age}
   * old (younger ones are only counted as deferred and picked up by a later reconciliation).
   * Gateway errors are reported in the result rather than thrown.
   */
  public synchronized Result reconcile() {
    if (!props.enabled()) {
      return new Result(0, subscribed.size(), 0, 0, 0, "实时订阅未启用");
    }
    if (paused) {
      return new Result(desired().size(), subscribed.size(), 0, 0, 0, "已暂停");
    }
    Set<Instrument> want = desired();
    List<Instrument> toAdd = new ArrayList<>();
    for (Instrument i : want) {
      if (!subscribed.containsKey(i)) {
        toAdd.add(i);
      }
    }
    List<Instrument> toRemove = new ArrayList<>();
    int deferred = 0;
    Instant now = clock.instant();
    for (Map.Entry<Instrument, Instant> e : subscribed.entrySet()) {
      if (want.contains(e.getKey())) {
        continue;
      }
      if (Duration.between(e.getValue(), now).compareTo(props.unsubscribeMinAge()) >= 0) {
        toRemove.add(e.getKey());
      } else {
        deferred++;
      }
    }
    int added = 0;
    int removed = 0;
    String error = null;
    try {
      for (List<Instrument> chunk : chunks(toAdd)) {
        added += subscribeChunk(chunk, now);
      }
      for (List<Instrument> chunk : chunks(toRemove)) {
        removed += unsubscribeChunk(chunk);
      }
      refreshQuota();
    } catch (Exception e) {
      error = rootMessage(e);
      log.warn("实时订阅对账失败：{}", error);
    }
    lastReconcileAt = now;
    lastError = error;
    if (added > 0 || removed > 0) {
      log.info(
          "实时订阅对账：期望 {}，已订 {}，新增 {}，反订阅 {}，延后 {}",
          want.size(),
          subscribed.size(),
          added,
          removed,
          deferred);
    }
    return new Result(want.size(), subscribed.size(), added, removed, deferred, error);
  }

  /**
   * Pause (manual): unsubscribe those at least 1 minute old and defer the younger ones (keeping
   * their records); while paused, reconciliation subscribes nothing.
   */
  public synchronized Result pause() {
    return pause(false);
  }

  /**
   * @param waitForYoung true = wait until the subscriptions younger than 1 minute are old enough
   *     and then unsubscribe them as well (a rotation needs all of the quota released first); waits
   *     at most unsubscribe-min-age (plus 1 second), holding the monitor while it sleeps
   */
  public synchronized Result pause(boolean waitForYoung) {
    paused = true;
    Result r = unsubscribeAged("暂停");
    if (waitForYoung && !subscribed.isEmpty()) {
      Instant youngest =
          subscribed.values().stream().max(Instant::compareTo).orElse(clock.instant());
      Duration wait = props.unsubscribeMinAge().minus(Duration.between(youngest, clock.instant()));
      if (!wait.isNegative() && !wait.isZero()) {
        log.info("有 {} 个订阅未满 1 分钟，等待 {} 秒后再反订阅", subscribed.size(), wait.toSeconds());
        try {
          sleeper.sleep(wait.plusSeconds(1));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      r = unsubscribeAged("暂停");
    }
    cache.clear();
    return r;
  }

  public synchronized Result resume() {
    paused = false;
    return reconcile();
  }

  /**
   * Unsubscribes every subscription at least 1 minute old; younger ones stay in the subscribed set
   * (deferred).
   */
  private Result unsubscribeAged(String why) {
    Instant now = clock.instant();
    List<Instrument> aged = new ArrayList<>();
    int deferred = 0;
    for (Map.Entry<Instrument, Instant> e : subscribed.entrySet()) {
      if (Duration.between(e.getValue(), now).compareTo(props.unsubscribeMinAge()) >= 0) {
        aged.add(e.getKey());
      } else {
        deferred++;
      }
    }
    int removed = 0;
    String error = null;
    try {
      for (List<Instrument> chunk : chunks(aged)) {
        gateway.unsubscribeQuotes(chunk).get(30, TimeUnit.SECONDS);
        chunk.forEach(subscribed::remove);
        removed += chunk.size();
      }
      refreshQuota();
    } catch (Exception e) {
      error = rootMessage(e);
      log.warn("{}时反订阅失败：{}", why, error);
    }
    lastError = error;
    return new Result(desired().size(), subscribed.size(), 0, removed, deferred, error);
  }

  public boolean paused() {
    return paused;
  }

  public synchronized Status status() {
    int deferred = 0;
    Set<Instrument> want = desired();
    for (Instrument i : subscribed.keySet()) {
      if (!want.contains(i)) {
        deferred++;
      }
    }
    return new Status(
        props.enabled(),
        paused,
        want.size(),
        subscribed.size(),
        deferred,
        lastQuota,
        lastReconcileAt,
        lastError);
  }

  public SubscriptionInfo refreshQuota() {
    try {
      lastQuota = gateway.subscriptionInfo().get(15, TimeUnit.SECONDS);
    } catch (Exception e) {
      log.debug("订阅额度查询失败：{}", e.toString());
    }
    return lastQuota;
  }

  /**
   * Reconciliation after a pool change (the {@code PoolService.afterChange} hook).
   *
   * <p>Does nothing when auto-subscribe=false and there are no subscriptions at all: that is the
   * dev instance's normal state. Previously a pool change (manually adding / removing members,
   * holdings sync) called {@link #reconcile()} directly, so the dev instance subscribed to
   * real-time quotes as well, at the same time as production, consuming double quota on the same
   * account. An instance that was reconciled manually (has subscriptions) keeps following the pool,
   * so the subscribed set does not drift from the pool.
   */
  public Result onPoolChanged() {
    boolean hasSubscriptions;
    synchronized (this) {
      hasSubscriptions = !subscribed.isEmpty();
    }
    if (!props.autoSubscribe() && !hasSubscriptions) {
      log.info("标的池有变动；auto-subscribe=false 且当前没有订阅，不对账");
      return new Result(0, 0, 0, 0, 0, "auto-subscribe=false 且当前没有订阅，未对账");
    }
    return reconcile();
  }

  // ------------------------------------------------------------------ GatewayListener (Futu)

  @Override
  public void onConnected(Broker broker, boolean reconnected) {
    if (broker != Broker.FUTU) {
      return;
    }
    synchronized (this) {
      subscribed.clear(); // a new connection carries no subscriptions
      cache.clear();
    }
    if (reconnected || props.autoSubscribe()) {
      // Hand off to our own thread: do not block the 2-thread futu-scheduler for tens of seconds,
      // or heartbeats cannot be scheduled and the connection is judged dead.
      // Note: on reconnect this reconciles even with auto-subscribe=false and nothing subscribed
      // before the disconnect (onDisconnected already cleared that information)
      worker.execute(
          () -> {
            try {
              Result r = reconcile();
              log.info("富途{}，实时订阅对账：{}", reconnected ? "重连" : "连上", r);
            } catch (RuntimeException e) {
              log.warn("富途{}后的实时订阅对账失败：{}", reconnected ? "重连" : "连上", e.toString());
            }
          });
    }
  }

  @Override
  public void onDisconnected(Broker broker, String reason) {
    if (broker != Broker.FUTU) {
      return;
    }
    synchronized (this) {
      subscribed.clear();
    }
  }

  /**
   * Waits for the reconciliations queued on {@link #worker} to finish. <b>Tests only</b>: the
   * reconciliation after connect / reconnect is asynchronous, so a test asserting the subscription
   * result must wait for it first, or it races.
   */
  void awaitReconcile() {
    try {
      worker.submit(() -> {}).get(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  // ------------------------------------------------------------------ Rotation coordination

  /**
   * Called on the job thread before a rotation. With pause-during-refresh: release every real-time
   * subscription (waiting out the young ones) and return 0 so the rotation uses its configured
   * batch size. Without it: keep the subscriptions and return the remaining quota minus the reserve
   * as the batch cap.
   */
  @Override
  public int beforeRefresh() {
    if (!props.enabled()) {
      return 0;
    }
    if (props.pauseDuringRefresh()) {
      Result r;
      synchronized (this) {
        if (paused) {
          log.info("全量轮转开始；实时订阅已处于暂停，保持原状");
          return 0;
        }
        resubscribeAfterRefresh = props.autoSubscribe() || !subscribed.isEmpty();
        pausedByRefresh = true;
        r = pause(true);
      }
      log.info("全量轮转开始，暂停实时订阅：{}", r);
      return 0;
    }
    SubscriptionInfo q = refreshQuota();
    return q == null ? 0 : Math.max(1, q.remainQuota() - props.reserveQuota());
  }

  /**
   * Only lifts a pause the rotation started itself. An instance that does not auto-subscribe and
   * had no subscriptions before the rotation (the dev instance) only clears the pause and does not
   * reconcile: before 2.0.2 this reconciled unconditionally, so the dev instance subscribed to
   * real-time quotes after every rotation, sharing one account's quota with production.
   */
  @Override
  public void afterRefresh() {
    boolean resubscribe;
    synchronized (this) {
      if (!pausedByRefresh) {
        return;
      }
      pausedByRefresh = false;
      resubscribe = resubscribeAfterRefresh;
      if (!resubscribe) {
        paused = false;
      }
    }
    if (!resubscribe) {
      log.info("全量轮转结束，解除暂停；auto-subscribe=false 且轮转前没有订阅，不对账");
      return;
    }
    Result r = resume();
    log.info("全量轮转结束，恢复实时订阅：{}", r);
  }

  /**
   * Subscribes one chunk; when an unknown symbol poisons it, drop that symbol and re-subscribe
   * once, so the rest of the chunk still gets quotes. Only successfully subscribed instruments are
   * recorded, with {@code now} as their age reference for the 1-minute unsubscribe rule.
   */
  private int subscribeChunk(List<Instrument> chunk, Instant now) throws Exception {
    try {
      gateway.subscribeQuotes(chunk).get(30, TimeUnit.SECONDS);
      chunk.forEach(i -> subscribed.put(i, now));
      return chunk.size();
    } catch (Exception e) {
      List<Instrument> kept = purge(chunk, e);
      if (kept == null) {
        throw e;
      }
      if (kept.isEmpty()) {
        return 0;
      }
      gateway.subscribeQuotes(kept).get(30, TimeUnit.SECONDS);
      kept.forEach(i -> subscribed.put(i, now));
      return kept.size();
    }
  }

  /**
   * Unsubscribes one chunk; when unknown symbols poison it, remove them from the local book and
   * retry once.
   *
   * <p>This path is a new trap introduced by marking UNRESOLVED: once marked, the instrument drops
   * out of {@link #desired()}, so every reconciliation puts it into toRemove, while the broker does
   * not know the symbol at all and has no subscription to cancel. Left in {@code subscribed}, it
   * would make <b>every</b> reconciliation fail and freeze real-time quotes. Since the broker does
   * not know it, removing it locally is the correct result.
   */
  private int unsubscribeChunk(List<Instrument> chunk) throws Exception {
    try {
      gateway.unsubscribeQuotes(chunk).get(30, TimeUnit.SECONDS);
      chunk.forEach(subscribed::remove);
      cache.remove(chunk);
      return chunk.size();
    } catch (Exception e) {
      List<Instrument> kept = purge(chunk, e);
      if (kept == null) {
        throw e;
      }
      List<Instrument> gone = chunk.stream().filter(i -> !kept.contains(i)).toList();
      gone.forEach(subscribed::remove);
      cache.remove(gone);
      if (kept.isEmpty()) {
        return gone.size();
      }
      gateway.unsubscribeQuotes(kept).get(30, TimeUnit.SECONDS);
      kept.forEach(subscribed::remove);
      cache.remove(kept);
      return chunk.size();
    }
  }

  /**
   * The chunk minus the symbols Futu does not know; {@code null} when the guard did not intervene
   * or found no bad symbol (the caller rethrows as before). Passes {@code mayMark=false}: pool and
   * holdings deserve a human look, and the daily increment covers the same instruments, where
   * marking happens with a JobContext that can record PARTIAL; the reconciliation thread does not
   * need to write to the database silently.
   */
  private List<Instrument> purge(List<Instrument> chunk, Exception failure) {
    UnknownSymbolGuard.Outcome out = unknownSymbols.inspect(chunk, failure, false).orElse(null);
    if (out == null || !out.canRetry()) {
      return null;
    }
    log.warn("实时订阅对账：{}", out.detail());
    return chunk.stream()
        .filter(i -> !out.exclude().contains(i.symbol().toUpperCase(Locale.ROOT)))
        .toList();
  }

  private static List<List<Instrument>> chunks(List<Instrument> list) {
    List<List<Instrument>> out = new ArrayList<>();
    for (int i = 0; i < list.size(); i += SUB_CHUNK) {
      out.add(list.subList(i, Math.min(list.size(), i + SUB_CHUNK)));
    }
    return out;
  }

  private static String rootMessage(Throwable t) {
    Throwable c = t;
    while (c.getCause() != null) {
      c = c.getCause();
    }
    return c.getMessage() == null ? c.toString() : c.getMessage();
  }
}
