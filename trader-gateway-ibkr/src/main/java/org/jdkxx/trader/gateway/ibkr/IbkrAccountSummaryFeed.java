package org.jdkxx.trader.gateway.ibkr;

import com.ib.client.EClientSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.jdkxx.trader.domain.AccountSummary;
import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.gateway.GatewayException;
import org.jdkxx.trader.gateway.LiveAccountListener;
import org.jdkxx.trader.gateway.NotConnectedException;
import org.jdkxx.trader.gateway.RequestRejectedException;
import org.jdkxx.trader.gateway.ibkr.mapper.IbkrAccounts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The <b>standing subscription</b> for the account summary: subscribed on connect, cancelled only
 * on disconnect, exactly once per connection cycle, shared by the live account and the daily
 * snapshot.
 *
 * <p>Why not "subscribe, use, cancel" any more (measured in production on 2026-09-23, changed in
 * 3.0.9): IBKR's limit of 2 per client counts <b>subscriptions ever made</b>, not the ones
 * currently alive. {@code cancelAccountSummary} goes out, yet the slot is not released; only a
 * client reconnect resets the count. That day's timeline matches step by step: 22:04 the live
 * account's idle unsubscribe had taken slot 1, the next day at 06:00 the snapshot job took slot 2
 * (so the job itself succeeded), at 06:53 the live account's re-subscribe was the 3rd and got 322,
 * and every later attempt was rejected until a manual disconnect and reconnect. 3.0.8's "send a
 * cancel before subscribing" could not save this scenario: it only remembered the live account's
 * own previous reqId and could not cancel the one the snapshot job left behind.
 *
 * <p><b>This is a workaround, not a fix</b>: why IBKR does not release the slot is still unknown.
 * All this class guarantees is a single subscription per connection cycle, so whether slots are
 * released no longer affects correctness. After a 1101 data loss it re-subscribes ({@link
 * #subscribe(boolean)}), which takes one more slot; if that is rejected it goes through the 322
 * retry, and if all three are rejected only a reconnect helps. Production has never seen a 1101 so
 * far; this path is unverified against real data.
 *
 * <p>Threading: every method, the subscription handler and every {@link IbkrLiveAccount.Wire#later}
 * task run on the dispatch thread, so the state below needs no locks.
 */
final class IbkrAccountSummaryFeed {

  private static final Logger log = LoggerFactory.getLogger(IbkrAccountSummaryFeed.class);

  /** Error code when the account summary hits the 2-per-client limit */
  static final int LIMIT_CODE = 322;

  static final long RETRY_SECONDS = 3;
  static final int MAX_RETRIES = 3;

  /**
   * Batching of the one-by-one pushes: the broker sends one tag per callback and no End after the
   * first batch.
   */
  static final long DEBOUNCE_MS = 300;

  /**
   * Acceptable staleness for a snapshot read: the broker pushes a batch roughly every 3 minutes
   * (measured), with a threefold margin.
   */
  static final Duration MAX_AGE = Duration.ofMinutes(10);

  /** How long to wait at most when no batch has arrived yet (right after a reconnect). */
  static final Duration WAIT = Duration.ofSeconds(90);

  private final IbkrLiveAccount.Wire wire;

  /** The session in which we subscribed; null = no subscription right now */
  private Object bound;

  private Integer subId;

  /**
   * The previous request id, kept across sessions: a cancel for it is sent before re-subscribing
   * (harmless; if IBKR does not recognize it, it just answers with a system message).
   */
  private Integer staleId;

  private int retries;
  private Runnable retry;
  private Runnable flush;

  private final Map<String, IbkrAccounts.SummaryRow> rows = new LinkedHashMap<>();
  private List<IbkrAccounts.SummaryRow> snapshot = List.of();
  private Instant updatedAt;
  private String lastError;

  /**
   * The live account's listener and the account it cares about; both nullable (the subscription
   * stays even when nobody watches the live account).
   */
  private LiveAccountListener listener;

  private String liveAccountId;
  private final List<Waiter> waiters = new ArrayList<>();

  private record Waiter(
      String accountId, CompletableFuture<AccountSummary> future, Runnable cancelTimeout) {}

  IbkrAccountSummaryFeed(IbkrLiveAccount.Wire wire) {
    this.wire = wire;
  }

  /**
   * Attaches / detaches the live account's listener ({@code accountId} is the account it cares
   * about). When attaching and data already exists, one update is delivered immediately instead of
   * waiting for the next batch; that is the point of a standing subscription: reading again after
   * an idle unsubscribe does not mean waiting 3 minutes.
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

  /**
   * Subscribe. Does nothing if already subscribed: <b>the whole point of standing is never
   * subscribing twice</b>.
   */
  boolean subscribe() {
    return subscribe(false);
  }

  /**
   * @param afterRecovery the connection has just recovered: a reconnect after a drop, or 1101
   *     "connection restored, subscription data lost".
   *     <p>Why this parameter exists (found in the full-project review on 2026-09-25): <b>on 1101
   *     the session object does not change</b> ({@code ConnectionSupervisor.notifyDataLost} only
   *     updates the detail and dispatches {@code onConnected(broker, true)}, leaving the session
   *     alone), so {@link #subscribed()} is still true and {@code subscribe()} short-circuits on
   *     its first line, neither cancelling nor re-sending, while on the broker's side the
   *     subscription is gone. The result was frozen funds data: {@code updatedAt} stops advancing,
   *     after {@link #MAX_AGE} reads go into the 90-second wait and fail, the 18:00 account
   *     snapshot job is FAILED and the 22:00 catch-up fails too, while {@code GET
   *     /api/account/live} keeps showing the net liquidation from before the 1101 with its status
   *     still LIVE. This class's comment and ARCHITECTURE §20.7 both said "re-subscribes after
   *     1101", but that re-subscribe <b>did not exist in the code at all</b>; {@code
   *     IbkrLiveAccount.subscribe()} in the same package explicitly does {@code cancelAll()} first
   *     for the same session, and the two ought to agree.
   *     <p>On a real reconnect the session has already changed, {@code subscribed()} is false
   *     anyway, and the old path is taken.
   *     <p><b>The cost, stated plainly</b>: this re-subscribe takes one more of the "2 per client"
   *     slots, and cancelling does not free slots (measured). So on a second 1101 within one
   *     connection cycle the re-subscribe is rejected with 322 and goes through {@link
   *     #retrySubscribe()}; if all three attempts are rejected only a reconnect helps. That is
   *     still better than freezing silently: 322 goes into the log and is visible to the daily
   *     check. <b>Production has never seen a 1101 so far; this path is unverified against real
   *     data.</b>
   */
  boolean subscribe(boolean afterRecovery) {
    Object current = wire.sessionToken();
    if (current == null) {
      return false;
    }
    if (subscribed() && !afterRecovery) {
      return true;
    }
    if (subscribed()) {
      log.info("连接恢复（会话未变，多半是 1101 数据丢失）：重订账户汇总常驻订阅，这一次会再占一个名额");
      releaseForResubscribe();
    }
    forget();
    bound = current;
    retries = 0;
    cancelStale();
    return open();
  }

  /**
   * Before re-subscribing within the same session, cancel the old subscription first. That does not
   * free the slot (measured 2026-09-23), but without it the broker would keep an orphaned
   * subscription pushing rows we no longer recognize.
   */
  private void releaseForResubscribe() {
    if (subId == null) {
      return;
    }
    wire.close(subId);
    int id = subId;
    if (staleId != null && staleId == id) {
      staleId = null; // never cancel the same id twice
    }
    wire.unsubscribe("账户汇总（连接恢复前的那条）", c -> c.cancelAccountSummary(id));
    subId = null;
  }

  /**
   * Cancels on disconnect / shutdown. On a real disconnect the cancel is a no-op (the connection is
   * gone), but the waiters fail right away instead of sitting out {@link #WAIT}.
   */
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
   * Reads the account summary: served immediately if fresh; if there is none yet or it is too old,
   * waits for the next batch (at most {@link #WAIT}). <b>Called only on the dispatch thread.</b>
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

  /** Fresh = at least one batch has arrived and it is no older than {@link #MAX_AGE}. */
  private boolean fresh() {
    return updatedAt != null
        && !snapshot.isEmpty()
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
    out.completeExceptionally(
        new GatewayException(
            Broker.IBKR,
            0,
            "账户汇总常驻订阅 " + WAIT.toSeconds() + " 秒内没有推送" + (lastError == null ? "" : "：" + lastError),
            true));
  }

  private boolean open() {
    int id = wire.nextId();
    wire.open(id, handler());
    if (!wire.subscribe(
        "账户汇总（常驻）", c -> c.reqAccountSummary(id, "All", IbkrAccounts.SUMMARY_TAGS))) {
      wire.close(id);
      subId = null;
      bound = null;
      return false;
    }
    subId = id;
    staleId = id;
    return true;
  }

  /** IBKR may still hold the previous subscription: send one cancel for the remembered id. */
  private void cancelStale() {
    Integer id = staleId;
    if (id == null) {
      return;
    }
    staleId = null;
    wire.unsubscribe("账户汇总（上一次）", (Consumer<EClientSocket>) c -> c.cancelAccountSummary(id));
  }

  /**
   * Rejected with 322: cancel this one together with any leftover, then re-subscribe a few seconds
   * later, at most {@value #MAX_RETRIES} times.
   */
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
        staleId = null; // never cancel the same id twice
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
        rows.put(
            r.account() + "|" + r.tag(),
            r); // the "All" group may contain several accounts: key by account + tag
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
          retry =
              wire.later(
                  Duration.ofSeconds(RETRY_SECONDS), IbkrAccountSummaryFeed.this::retrySubscribe);
        }
      }
    };
  }

  /**
   * Publishes the rows accumulated so far as one batch: the immutable snapshot and its timestamp,
   * the live listener, then every waiting snapshot request. Rows are only ever overwritten tag by
   * tag, never dropped, so a tag the broker stops sending keeps its last value.
   */
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
