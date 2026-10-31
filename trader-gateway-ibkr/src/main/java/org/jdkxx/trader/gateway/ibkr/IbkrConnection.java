package org.jdkxx.trader.gateway.ibkr;

import com.ib.client.Bar;
import com.ib.client.Contract;
import com.ib.client.ContractDetails;
import com.ib.client.EClientSocket;
import com.ib.client.EJavaSignal;
import com.ib.client.EReader;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.jdkxx.trader.common.ratelimit.RateLimiter;
import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.gateway.GatewayException;
import org.jdkxx.trader.gateway.NotConnectedException;
import org.jdkxx.trader.gateway.ibkr.mapper.IbkrAccounts;
import org.jdkxx.trader.gateway.support.Transport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One socket session to IB Gateway / TWS and its threads:
 *
 * <ul>
 *   <li>{@code ibkr-connect}: creates the socket (with a connect timeout) and runs the
 *       synchronous-handshake eConnect (with a read timeout during the handshake)
 *   <li>{@code ibkr-reader}: the SDK's EReader, which slices incoming bytes into messages on a
 *       queue
 *   <li>{@code ibkr-pump}: loops {@code waitForSignal / processMsgs}; the EWrapper data callbacks
 *       run on this thread
 * </ul>
 *
 * Every open() builds a fresh set (neither EClientSocket nor EWrapper is reused), and callbacks act
 * only on the session that produced them; the readiness signal is nextValidId.
 *
 * <p>Lock discipline: EClientSocket's eConnect / isConnected / eDisconnect are all synchronized
 * (verified with javap against 10.30.01; so are all the EClient request and cancel methods), and
 * while a handshake is stuck eConnect holds that lock the whole time. The state machine calls
 * open() / close() while holding its own lock, so those two methods never touch the client's lock:
 * close first closes the underlying socket, which kicks the stuck read out, and runs eDisconnect on
 * the dispatch thread. Before 2.0.2 close() called isConnected() directly, and an unanswered
 * handshake hung the state machine's scheduler thread forever.
 */
final class IbkrConnection implements Transport {

  private static final Logger log = LoggerFactory.getLogger(IbkrConnection.class);

  private final IbkrProperties props;
  private final IbkrRequestRegistry registry;
  private final IbkrSubscriptions subscriptions;
  private final IbkrFacts facts;
  private final Executor dispatch;
  private final RateLimiter limiter;
  private final ExecutorService connectExecutor;
  private final RepeatSuppressor errorLog =
      new RepeatSuppressor(Duration.ofMinutes(10), Clock.systemUTC());

  /** Waiters for requests that carry no reqId: replies are matched to waiters in FIFO order. */
  private final ConcurrentLinkedQueue<CompletableFuture<Instant>> timeWaiters =
      new ConcurrentLinkedQueue<>();

  private final ConcurrentLinkedQueue<CompletableFuture<List<String>>> accountWaiters =
      new ConcurrentLinkedQueue<>();

  private volatile Consumer<String> closedHandler = reason -> {};
  private volatile Consumer<String> dataLostHandler = reason -> {};
  private volatile Session session;

  /**
   * Everything that belongs to one connection attempt. {@code closed} flips exactly once and makes
   * {@link #sessionEnded} idempotent; {@code intentional} marks closes we initiated, which must not
   * be reported to the supervisor as a broken connection.
   */
  private static final class Session {
    final EJavaSignal signal = new EJavaSignal();
    final CompletableFuture<Void> ready = new CompletableFuture<>();
    final AtomicBoolean closed = new AtomicBoolean();
    volatile EClientSocket client;
    volatile Socket socket;
    volatile EReader reader;
    volatile Thread pump;
    volatile boolean intentional;
  }

  IbkrConnection(
      IbkrProperties props,
      IbkrRequestRegistry registry,
      IbkrSubscriptions subscriptions,
      IbkrFacts facts,
      Executor dispatch,
      RateLimiter limiter) {
    this.props = props;
    this.registry = registry;
    this.subscriptions = subscriptions;
    this.facts = facts;
    this.dispatch = dispatch;
    this.limiter = limiter;
    this.connectExecutor =
        Executors.newSingleThreadExecutor(
            r -> {
              Thread t = new Thread(r, "ibkr-connect");
              t.setDaemon(true);
              return t;
            });
  }

  void onClosed(Consumer<String> handler) {
    this.closedHandler = handler;
  }

  void onDataLost(Consumer<String> handler) {
    this.dataLostHandler = handler;
  }

  // ------------------------------------------------------------------ Transport

  @Override
  public CompletableFuture<Void> open() {
    Session old = session;
    if (old != null) {
      // End the old session first: its in-flight requests fail before the new session has any
      // requests, and the old socket does not linger holding the client-id
      old.intentional = true;
      closeSession(old, "被新的连接取代");
    }
    Session s = new Session();
    s.client =
        new EClientSocket(new IbkrWrapper(new SessionEvents(s), registry, subscriptions), s.signal);
    session = s;
    facts.reset();
    // The blocking handshake runs on its own thread so the caller (the state machine, under its
    // lock) never waits on it
    connectExecutor.execute(() -> handshake(s));
    return s.ready;
  }

  private void handshake(Session s) {
    int timeoutMs = (int) Math.max(1, props.connectTimeout().toMillis());
    Socket socket = new Socket();
    // Published before connecting so a concurrent closeSession can close it and abort a stuck
    // connect / read
    s.socket = socket;
    try {
      if (s.closed.get()) {
        closeQuietly(socket);
        return;
      }
      socket.connect(new InetSocketAddress(props.host(), props.port()), timeoutMs);
      // Read timeout during the handshake: when the port is listening but the peer never answers
      // (the tunnel's local port is still up while the link is down), the SDK's read of the server
      // version must not block forever
      socket.setSoTimeout(timeoutMs);
      s.client.eConnect(socket, props.clientId());
      if (!s.client.isConnected()) {
        s.ready.completeExceptionally(
            new GatewayException(
                Broker.IBKR, 502, "连不上 IB Gateway / TWS：确认网关在运行、端口与隧道正确、API 设置里允许了本机连接", true));
        closeSession(s, "握手失败");
        return;
      }
      // Handshake done: back to blocking reads, the EReader thread waits on the socket indefinitely
      // from here on
      socket.setSoTimeout(0);
      facts.serverVersion(s.client.serverVersion());
      facts.connectionTime(s.client.getTwsConnectionTime());
      s.reader = new EReader(s.client, s.signal);
      s.reader.setName("ibkr-reader");
      s.reader.setDaemon(true);
      s.reader.start();
      s.pump = new Thread(() -> pump(s), "ibkr-pump");
      s.pump.setDaemon(true);
      s.pump.start();
    } catch (Throwable t) {
      s.ready.completeExceptionally(
          new GatewayException(Broker.IBKR, 502, "建连失败：" + describeIo(t), true, t));
      closeSession(s, "建连失败");
    }
  }

  /**
   * Deliberately leaves out the exception text: an UnknownHostException's message is the host name,
   * and the host is sensitive configuration.
   */
  private static String describeIo(Throwable t) {
    if (t instanceof SocketTimeoutException) {
      return "连接或握手超时（端口可达但对端没有应答，检查网关状态与隧道）";
    }
    if (t instanceof ConnectException) {
      return "连接被拒绝或不可达（确认网关在运行、端口与隧道正确）";
    }
    if (t instanceof UnknownHostException) {
      return "主机名解析失败";
    }
    return t.getClass().getSimpleName();
  }

  /**
   * Message pump. The loop ends once the client reports disconnected; that happens when EReader
   * hits an I/O error (it calls eDisconnect itself and then signals) or when the eDisconnect queued
   * by closeSession has run.
   */
  private void pump(Session s) {
    while (s.client.isConnected()) {
      s.signal.waitForSignal();
      try {
        s.reader.processMsgs();
      } catch (Exception e) {
        log.warn("处理 TWS 报文时出错：{}", e.toString());
      }
    }
    sessionEnded(s, "与网关的连接已断开");
  }

  @Override
  public void close() {
    Session s = session;
    if (s == null) {
      return;
    }
    s.intentional = true;
    closeSession(s, "主动断开");
  }

  /**
   * Never touches the client's object lock (see the class comment); safe to call from any thread
   * while holding any lock.
   */
  private void closeSession(Session s, String reason) {
    Socket socket = s.socket;
    if (socket != null) {
      closeQuietly(
          // an eConnect stuck on the handshake read returns immediately and releases the client
          // lock
          socket);
    }
    s.signal.issueSignal();
    sessionEnded(s, reason);
    // eDisconnect is synchronized on the client: run it on dispatch so the caller never waits on
    // the SDK lock.
    // Note that eDisconnect itself invokes EWrapper.connectionClosed() on the calling thread.
    Runnable disconnect =
        () -> {
          try {
            s.client.eDisconnect();
          } catch (RuntimeException e) {
            log.debug("eDisconnect 出错：{}", e.toString());
          }
        };
    try {
      dispatch.execute(disconnect);
    } catch (RejectedExecutionException e) {
      disconnect.run(); // shutting down: the socket is already closed, so this will not wait long
    }
  }

  private static void closeQuietly(Socket socket) {
    try {
      socket.close();
    } catch (IOException e) {
      // ignore
    }
  }

  /**
   * Tears down everything that depended on this session, exactly once (guarded by {@code closed}).
   * Reached from the pump loop, from {@code connectionClosed} (which the SDK raises inside
   * eDisconnect, i.e. on the reader thread or on dispatch) and from closeSession. The supervisor is
   * told only about unintentional ends of the current session; its handler hops to the scheduler
   * thread itself.
   */
  private void sessionEnded(Session s, String reason) {
    if (!s.closed.compareAndSet(false, true)) {
      return;
    }
    NotConnectedException gone = new NotConnectedException(Broker.IBKR, reason);
    registry.failAll(gone);
    subscriptions
        // subscriptions die with the session; subscribers re-subscribe with new reqIds after
        // reconnecting
        .clear();
    failWaiters(gone);
    if (!s.ready.isDone()) {
      s.ready.completeExceptionally(new GatewayException(Broker.IBKR, 0, reason, true));
    }
    if (!s.intentional && session == s) {
      closedHandler.accept(reason);
    }
  }

  @Override
  public CompletableFuture<Boolean> probe() {
    CompletableFuture<Instant> waiter = currentTime();
    CompletableFuture<Boolean> probe = waiter.thenApply(t -> true);
    // The state machine fails the probe on heartbeat timeout; the original waiter must be dequeued
    // then, otherwise every later reply is matched to the previous, already abandoned waiter and
    // the heartbeat keeps failing
    probe.whenComplete(
        (ok, ex) -> {
          if (ex != null) {
            timeWaiters.remove(waiter);
          }
        });
    return probe;
  }

  @Override
  public boolean isOpen() {
    return isConnected();
  }

  // ------------------------------------------------------------------ requests

  /**
   * Ready and not closed. The {@code ready} checks come first on purpose: while a handshake is in
   * progress they short-circuit before {@code client.isConnected()}, which is synchronized and
   * would block behind the stuck eConnect.
   */
  boolean isConnected() {
    Session s = session;
    return s != null
        && s.ready.isDone()
        && !s.ready.isCompletedExceptionally()
        && !s.closed.get()
        && s.client.isConnected();
  }

  CompletableFuture<Instant> currentTime() {
    CompletableFuture<Instant> f = new CompletableFuture<>();
    timeWaiters.add(
        f); // enqueue before sending: the reply may arrive before we would otherwise enqueue
    if (!send(EClientSocket::reqCurrentTime, f)) {
      timeWaiters.remove(f);
    }
    return f;
  }

  CompletableFuture<List<String>> managedAccounts() {
    List<String> known = facts.managedAccounts();
    if (!known.isEmpty()) {
      return CompletableFuture.completedFuture(known);
    }
    CompletableFuture<List<String>> f = new CompletableFuture<>();
    accountWaiters.add(f);
    if (!send(EClientSocket::reqManagedAccts, f)) {
      accountWaiters.remove(f);
    }
    return f;
  }

  CompletableFuture<List<ContractDetails>> contractDetails(Contract contract) {
    int id = registry.nextId();
    CompletableFuture<List<ContractDetails>> f =
        registry.open(
            id,
            "合约查询 " + contract.symbol(),
            new PendingRequest.Many<>(ContractDetails.class),
            null);
    sendWithId(id, c -> c.reqContractDetails(id, contract));
    return f;
  }

  /**
   * Historical daily bars (reqHistoricalData: TRADES, 1 day, regular trading hours only, date
   * format yyyyMMdd, no keep-up-to-date). A one-shot request that ends naturally at End; on timeout
   * it is cancelled so the gateway does not keep the request occupying a historical-data pacing
   * slot. The timeout cancel runs on the scheduler thread.
   */
  CompletableFuture<List<Bar>> historicalDailyBars(
      Contract contract, String endDateTime, String duration, Duration timeout) {
    int id = registry.nextId();
    CompletableFuture<List<Bar>> f =
        registry.open(
            id,
            "历史日线 conId=" + contract.conid(),
            new PendingRequest.Many<>(Bar.class),
            () -> cancelQuietly("历史日线", c -> c.cancelHistoricalData(id)),
            timeout);
    sendWithId(
        id,
        c ->
            c.reqHistoricalData(
                id, contract, endDateTime, duration, "1 day", "TRADES", 1, 1, false, null));
    return f;
  }

  /**
   * Earliest data time (reqHeadTimestamp: TRADES, regular hours only, formatDate=1, i.e. UTC
   * yyyyMMdd-HH:mm:ss). IBKR counts it as an open historical request, so it is cancelled as soon as
   * it ends, success or failure (official docs: always cancel).
   */
  CompletableFuture<String> headTimestamp(Contract contract, Duration timeout) {
    int id = registry.nextId();
    CompletableFuture<String> f =
        registry.open(
            id,
            "最早数据时间 conId=" + contract.conid(),
            new PendingRequest.Single<>(String.class),
            null,
            timeout);
    f.whenComplete((ts, ex) -> cancelQuietly("最早数据时间", c -> c.cancelHeadTimestamp(id)));
    sendWithId(id, c -> c.reqHeadTimestamp(id, contract, "TRADES", 1, 1));
    return f;
  }

  /**
   * Positions (reqPositionsMulti). It is a subscription-style request: the broker keeps pushing
   * changes after End, so it is cancelled as soon as the future ends (collected, failed or timed
   * out all count). The cancel follows the future and is sent on the dispatch thread, not on the
   * pump.
   */
  CompletableFuture<List<IbkrAccounts.PositionRow>> positions(String account) {
    int id = registry.nextId();
    CompletableFuture<List<IbkrAccounts.PositionRow>> f =
        registry.open(id, "持仓查询", new PendingRequest.Many<>(IbkrAccounts.PositionRow.class), null);
    f.whenComplete((rows, ex) -> cancelQuietly("持仓查询", c -> c.cancelPositionsMulti(id)));
    sendWithId(id, c -> c.reqPositionsMulti(id, account, ""));
    return f;
  }

  /**
   * Account summary (reqAccountSummary, group All; the caller filters by account). Also
   * subscription-style: cancelled when it ends. The gateway has a global concurrency limit on
   * account-summary subscriptions; concurrent requests are merged in {@link IbkrGateway}.
   */
  CompletableFuture<List<IbkrAccounts.SummaryRow>> accountSummary(String tags) {
    int id = registry.nextId();
    CompletableFuture<List<IbkrAccounts.SummaryRow>> f =
        registry.open(id, "账户汇总", new PendingRequest.Many<>(IbkrAccounts.SummaryRow.class), null);
    f.whenComplete((rows, ex) -> cancelQuietly("账户汇总", c -> c.cancelAccountSummary(id)));
    sendWithId(id, c -> c.reqAccountSummary(id, "All", tags));
    return f;
  }

  // ------------------------------------------------------------------ standing subscriptions (live
  // account)

  /**
   * Identity of the current session: subscribers use it to tell whether an old reqId is still alive
   * (on a 1101 data loss the session stays the same, so they must cancel first and then
   * re-subscribe).
   */
  Object sessionToken() {
    return session;
  }

  int nextId() {
    return registry.nextId();
  }

  /**
   * Sends a standing subscription. Returns false when not connected or the send fails; the
   * subscriber then waits for a reconnect and subscribes again.
   */
  boolean subscribe(String what, Consumer<EClientSocket> action) {
    Session s = session;
    if (!isConnected()) {
      return false;
    }
    try {
      limiter.acquire();
      action.accept(s.client);
      return true;
    } catch (RuntimeException e) {
      log.warn("订阅{}失败：{}", what, e.toString());
      return false;
    }
  }

  void unsubscribe(String what, Consumer<EClientSocket> action) {
    cancelQuietly(what, action);
  }

  /**
   * Sends a request that carries a reqId; when not connected or the send fails, the request in the
   * registry is failed.
   */
  private void sendWithId(int id, Consumer<EClientSocket> action) {
    Session s = session;
    if (!isConnected()) {
      registry.fail(id, new NotConnectedException(Broker.IBKR, "尚未连接"));
      return;
    }
    try {
      limiter.acquire();
      action.accept(s.client);
    } catch (RuntimeException e) {
      registry.fail(id, e);
    }
  }

  /**
   * Cancels a subscription-style request. No cancel is needed once the connection is gone
   * (subscriptions end with the session); a failed cancel is only logged.
   */
  private void cancelQuietly(String what, Consumer<EClientSocket> action) {
    Session s = session;
    if (!isConnected()) {
      return;
    }
    try {
      limiter.acquire();
      action.accept(s.client);
    } catch (RuntimeException e) {
      log.warn("取消{}失败：{}", what, e.toString());
    }
  }

  /**
   * Sends a request without a reqId; when not connected or the send fails, completes the future
   * exceptionally and returns false.
   */
  private boolean send(Consumer<EClientSocket> action, CompletableFuture<?> future) {
    Session s = session;
    if (!isConnected()) {
      future.completeExceptionally(new NotConnectedException(Broker.IBKR, "尚未连接"));
      return false;
    }
    try {
      limiter.acquire();
      action.accept(s.client);
      return true;
    } catch (RuntimeException e) {
      future.completeExceptionally(new GatewayException(Broker.IBKR, 0, "发送请求失败：" + e, true, e));
      return false;
    }
  }

  private void failWaiters(Throwable error) {
    CompletableFuture<?> w;
    while ((w = timeWaiters.poll()) != null) {
      CompletableFuture<?> f = w;
      dispatch.execute(() -> f.completeExceptionally(error));
    }
    while ((w = accountWaiters.poll()) != null) {
      CompletableFuture<?> f = w;
      dispatch.execute(() -> f.completeExceptionally(error));
    }
  }

  void shutdown() {
    close();
    connectExecutor.shutdownNow();
  }

  // ------------------------------------------------------------------ connection-level callbacks
  // (pump thread)

  /**
   * Callbacks bound to one session: a late nextValidId / disconnect from an old session never acts
   * on the new one.
   */
  private final class SessionEvents implements IbkrWrapper.ConnectionEvents {

    private final Session s;

    SessionEvents(Session s) {
      this.s = s;
    }

    private boolean current() {
      return session == s && !s.closed.get();
    }

    @Override
    public void onConnectAck() {
      log.debug("TWS connectAck");
    }

    @Override
    public void onNextValidId(int orderId) {
      if (!current()) {
        return;
      }
      facts.nextOrderId(orderId);
      if (!s.ready.isDone()) {
        errorLog.reset();
        s.ready.complete(null);
      }
    }

    @Override
    public void onManagedAccounts(String accounts) {
      if (!current()) {
        return;
      }
      List<String> list =
          Arrays.stream(accounts.split(",")).map(String::trim).filter(a -> !a.isEmpty()).toList();
      facts.managedAccounts(list);
      CompletableFuture<List<String>> w;
      while ((w = accountWaiters.poll()) != null) {
        CompletableFuture<List<String>> f = w;
        dispatch.execute(() -> f.complete(list));
      }
    }

    @Override
    public void onCurrentTime(long epochSeconds) {
      if (!current()) {
        return;
      }
      CompletableFuture<Instant> w = timeWaiters.poll();
      if (w != null) {
        dispatch.execute(() -> w.complete(Instant.ofEpochSecond(epochSeconds)));
      }
    }

    @Override
    public void onConnectionClosed() {
      sessionEnded(s, "网关关闭了连接（每日重启 / 对端退出 / client-id 冲突）");
    }

    @Override
    public void onSystemMessage(int code, String message) {
      if (current()) {
        systemMessage(code, message);
      }
    }
  }

  /**
   * Connection-level TWS codes. 1100 = gateway lost its link to IBKR (wait, it recovers by itself);
   * 1101 = restored but subscriptions were dropped (the session object is unchanged, so subscribers
   * must explicitly re-subscribe: reported to the supervisor as data loss); 1102 = restored with
   * data intact (nothing to do). 21xx are farm and informational notices; anything else is logged
   * through the repeat suppressor.
   */
  private void systemMessage(int code, String message) {
    switch (code) {
      case 2104, 2106, 2158 -> farm(message, "OK");
      case 2103, 2105 -> farm(message, "BROKEN");
      case 2107, 2108 -> farm(message, "INACTIVE");
      case 2119 -> farm(message, "CONNECTING");
      case 1100 -> {
        facts.connectivity("LOST");
        log.warn("TWS 1100：网关与 IB 的连接丢失，等待其自动恢复");
      }
      case 1101 -> {
        facts.connectivity("RESTORED_DATA_LOST");
        log.warn("TWS 1101：连接恢复但订阅数据丢失，需要重订阅");
        dataLostHandler.accept("1101 数据丢失");
      }
      case 1102 -> facts.connectivity("RESTORED");
      case 326 -> {
        facts.lastSystemMessage("326 client-id 已被占用");
        log.error("TWS 326：client-id 已被另一个实例占用，检查是否有别的实例用了同一个 id");
      }
      default -> {
        if (code >= 2100 && code < 2200) {
          log.info("TWS 提示 {}：{}", code, message);
        } else {
          facts.lastSystemMessage(code + " " + message);
          RepeatSuppressor.Decision d = errorLog.offer(code);
          if (d.log() && d.suppressed() == 0) {
            log.warn("TWS 错误 {}：{}", code, message);
          } else if (d.log()) {
            log.warn(
                "TWS 错误 {} 持续中（已 {} 分钟，期间又出现 {} 次）：{}",
                code,
                d.since().toMinutes(),
                d.suppressed(),
                message);
          }
        }
      }
    }
  }

  /**
   * Farm messages end with ":&lt;farm name&gt;"; the name becomes the {@code farm.*} key in the
   * status facts.
   */
  private void farm(String message, String state) {
    int i = message == null ? -1 : message.lastIndexOf(':');
    String name = i >= 0 && i < message.length() - 1 ? message.substring(i + 1).trim() : "?";
    facts.farm(name, state);
  }
}
