package org.jdkxx.trader.gateway.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.gateway.GatewayException;
import org.jdkxx.trader.gateway.GatewayListener;
import org.jdkxx.trader.gateway.GatewayState;
import org.jdkxx.trader.gateway.GatewayStatus;
import org.jdkxx.trader.gateway.RequestTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SDK-independent connection state machine: connect, readiness, heartbeat, disconnect detection,
 * reconnect with exponential backoff, and deliberate disconnect.
 *
 * <pre>
 * DISCONNECTED --connect()--&gt; CONNECTING --ready--&gt; CONNECTED
 *      ^                          | failed             | transport reports closed / consecutive heartbeat failures
 *      |                          v                    v
 *      +---disconnect()---- RECONNECTING &lt;-------------+
 * </pre>
 *
 * Non-retryable failures ({@link #reportFatal}, a non-retryable error from {@link
 * Transport#open()}, or exhausted reconnect attempts) end in ERROR; a later {@link #connect()}
 * starts over from there.
 *
 * <p>Threading model: state changes happen inside {@code lock}. Every future returned by the
 * Transport is brought back to the scheduler thread with {@code whenCompleteAsync(scheduler)};
 * events the transport reports on its own ({@link #onTransportClosed}, {@link #notifyDataLost}) are
 * also forwarded to the scheduler first, so an SDK callback thread never runs the state machine or
 * a listener. Listener notifications run on the scheduler, outside the lock, each as a separate
 * task. {@code generation} is bumped whenever an attempt or connection is abandoned, so that late
 * asynchronous results (for example an old connection that completes after disconnect) are
 * discarded.
 *
 * <p>{@link Transport#open()} and {@link Transport#close()} are called while holding the lock, so
 * neither may wait for an SDK lock (every method of the IBKR {@code EClientSocket} is synchronized,
 * and a stuck handshake holds that lock indefinitely).
 */
public final class ConnectionSupervisor {

  private static final Logger log = LoggerFactory.getLogger(ConnectionSupervisor.class);

  private final Broker broker;
  private final String name;
  private final Transport transport;
  private final SupervisorSettings settings;
  private final ScheduledExecutorService scheduler;
  private final Clock clock;
  private final Random random;
  private final List<GatewayListener> listeners = new CopyOnWriteArrayList<>();

  private final Object lock = new Object();
  private GatewayState state = GatewayState.DISCONNECTED;
  private String detail = "未连接";
  private Instant connectedSince;
  private Instant lastHeartbeatAt;
  private int attempts;
  private int heartbeatFailures;
  private boolean everConnected;
  private boolean wantConnected;
  private int generation;
  private CompletableFuture<Void> ready;
  private ScheduledFuture<?> heartbeatTask;
  private ScheduledFuture<?> reconnectTask;

  public ConnectionSupervisor(
      Broker broker,
      String name,
      Transport transport,
      SupervisorSettings settings,
      ScheduledExecutorService scheduler) {
    this(broker, name, transport, settings, scheduler, Clock.systemUTC(), new Random());
  }

  public ConnectionSupervisor(
      Broker broker,
      String name,
      Transport transport,
      SupervisorSettings settings,
      ScheduledExecutorService scheduler,
      Clock clock,
      Random random) {
    this.broker = broker;
    this.name = name;
    this.transport = transport;
    this.settings = settings;
    this.scheduler = scheduler;
    this.clock = clock;
    this.random = random;
  }

  public void addListener(GatewayListener listener) {
    listeners.add(listener);
  }

  // ------------------------------------------------------------------ public operations

  public CompletableFuture<Void> connect() {
    synchronized (lock) {
      wantConnected = true;
      if (state == GatewayState.CONNECTED) {
        return CompletableFuture.completedFuture(null);
      }
      if (ready == null || ready.isDone()) {
        ready = new CompletableFuture<>();
      }
      // An attempt is already in progress (including "a reconnect attempt is handshaking" or
      // waiting for its backoff delay): just wait for it. Before 2.0.2, RECONNECTING with an
      // attempt in flight started a second connect: the same client-id connected twice and the two
      // kicked each other off, and nobody closed the old attempt's socket
      if (state == GatewayState.CONNECTING || state == GatewayState.RECONNECTING) {
        return ready;
      }
      attempts = 0;
      startAttempt();
      return ready;
    }
  }

  public void disconnect() {
    CompletableFuture<Void> pending;
    boolean wasConnected;
    synchronized (lock) {
      wantConnected = false;
      generation++;
      cancelTasks();
      wasConnected = state == GatewayState.CONNECTED;
      safeClose();
      setState(GatewayState.DISCONNECTED, "已主动断开");
      pending = ready;
    }
    if (pending != null && !pending.isDone()) {
      pending.completeExceptionally(new GatewayException(broker, 0, name + " 在连接完成前被主动断开", false));
    }
    if (wasConnected) {
      notifyDisconnected("主动断开");
    }
  }

  /**
   * Non-retryable error (invalid configuration, account not among the managed accounts): stops
   * everything and enters ERROR. A pending {@link #connect()} future fails with {@code error}.
   */
  public void reportFatal(GatewayException error) {
    CompletableFuture<Void> pending;
    boolean wasConnected;
    synchronized (lock) {
      wantConnected = false;
      generation++;
      cancelTasks();
      wasConnected = state == GatewayState.CONNECTED;
      safeClose();
      setState(GatewayState.ERROR, error.getMessage());
      pending = ready;
    }
    if (pending != null && !pending.isDone()) {
      pending.completeExceptionally(error);
    }
    if (wasConnected) {
      notifyDisconnected(
          error
              // upper layers stop subscriptions and clear caches on this; before 2.0.2 only onError
              // was sent
              .getMessage());
    }
    dispatch(l -> l.onError(broker, error));
  }

  /**
   * The transport reports that the connection is gone (peer closed, reader thread exited). The
   * caller is usually an SDK callback thread, so this only hands the event over to the scheduler:
   * an SDK callback often holds the SDK's own lock, and taking the state-machine lock on that
   * thread would acquire the two locks in the opposite order from "state machine holds its lock and
   * calls into the SDK", which deadlocks.
   */
  public void onTransportClosed(String reason) {
    submit(() -> handleTransportClosed(reason));
  }

  /**
   * The connection is still up but the broker lost its side of the data (IBKR 1101): treated like a
   * reconnect so upper layers re-subscribe ({@code onConnected(broker, true)}), without touching
   * the transport. Also forwarded to the scheduler thread.
   */
  public void notifyDataLost(String reason) {
    submit(
        () -> {
          synchronized (lock) {
            if (state != GatewayState.CONNECTED) {
              return;
            }
            detail = "已连接（" + reason + "）";
          }
          dispatch(l -> l.onConnected(broker, true));
        });
  }

  // ------------------------------------------------------------------ queries

  public GatewayState state() {
    synchronized (lock) {
      return state;
    }
  }

  public boolean isConnected() {
    return state() == GatewayState.CONNECTED;
  }

  public GatewayStatus status(Map<String, String> facts) {
    synchronized (lock) {
      return new GatewayStatus(
          state, detail, clock.instant(), connectedSince, lastHeartbeatAt, attempts, facts);
    }
  }

  public String name() {
    return name;
  }

  // ------------------------------------------------------------------ internal: disconnects

  /**
   * Runs on the scheduler thread. Only a CONNECTED or CONNECTING supervisor reacts; while
   * RECONNECTING the in-flight attempt fails or times out on its own and {@link #onOpenResult}
   * handles it. The old connection is invalidated (generation bump), torn down, listeners are told,
   * and a fresh backoff sequence starts if a connection is still wanted.
   */
  private void handleTransportClosed(String reason) {
    synchronized (lock) {
      if (state != GatewayState.CONNECTED && state != GatewayState.CONNECTING) {
        return;
      }
      generation++;
      cancelTasks();
      safeClose();
      if (!wantConnected) {
        setState(GatewayState.DISCONNECTED, reason);
      }
    }
    notifyDisconnected(reason);
    synchronized (lock) {
      if (wantConnected) {
        attempts = 0;
        scheduleReconnect(reason);
      }
    }
  }

  // ------------------------------------------------------------------ internal: connect attempts

  /**
   * Starts one connect attempt; called with the lock held. The attempt gets its own generation, and
   * a connect timeout completes the transport's future exceptionally if "ready" never arrives. The
   * result is processed on the scheduler thread.
   */
  private void startAttempt() {
    int gen = ++generation;
    setState(
        everConnected || attempts > 0 ? GatewayState.RECONNECTING : GatewayState.CONNECTING,
        attempts == 0 ? "连接中" : "第 " + attempts + " 次重连中");
    CompletableFuture<Void> open;
    try {
      open = transport.open();
    } catch (RuntimeException e) {
      open = CompletableFuture.failedFuture(e);
    }
    final CompletableFuture<Void> opening = open;
    ScheduledFuture<?> timeout =
        scheduler.schedule(
            () -> opening.completeExceptionally(new RequestTimeoutException(broker, name + " 建连")),
            settings.connectTimeout().toMillis(),
            TimeUnit.MILLISECONDS);
    opening.whenCompleteAsync(
        (v, ex) -> {
          timeout.cancel(false);
          onOpenResult(gen, ex);
        },
        scheduler);
  }

  private void onOpenResult(int gen, Throwable ex) {
    // Between "ready" and this point the connection may already be gone (the gateway dropped it
    // right after the handshake). Ask the transport outside the lock, because it may need SDK locks
    Throwable failure = ex;
    if (failure == null && !transport.isOpen()) {
      failure = new GatewayException(broker, 0, name + " 连接刚就绪即断开", true);
    }
    boolean reconnected = false;
    GatewayException fatal = null;
    CompletableFuture<Void> pending;
    synchronized (lock) {
      if (gen != generation) {
        return; // stale result: disconnected or restarted in the meantime
      }
      if (!wantConnected) {
        safeClose();
        return;
      }
      pending = ready;
      if (failure != null) {
        safeClose();
        fatal = nonRetryable(failure);
        if (fatal == null) {
          scheduleReconnect(describe(failure));
          return;
        }
        // Configuration-type errors (e.g. an unreadable private key file) will not heal by
        // retrying; stop and wait for a human
        wantConnected = false;
        setState(GatewayState.ERROR, fatal.getMessage());
      } else {
        reconnected = everConnected;
        everConnected = true;
        attempts = 0;
        heartbeatFailures = 0;
        connectedSince = clock.instant();
        lastHeartbeatAt = connectedSince;
        setState(GatewayState.CONNECTED, reconnected ? "已重连" : "已连接");
        scheduleHeartbeat();
      }
    }
    if (fatal != null) {
      if (pending != null && !pending.isDone()) {
        pending.completeExceptionally(fatal);
      }
      GatewayException error = fatal;
      dispatch(l -> l.onError(broker, error));
      return;
    }
    if (pending != null) {
      pending.complete(null);
    }
    boolean re = reconnected;
    dispatch(l -> l.onConnected(broker, re));
  }

  /**
   * The non-retryable {@link GatewayException} behind {@code t} (unwrapping one
   * CompletionException), or null.
   */
  private static GatewayException nonRetryable(Throwable t) {
    Throwable c = t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
    return c instanceof GatewayException g && !g.retryable() ? g : null;
  }

  /**
   * Counts one more attempt and either gives up (ERROR, pending connect future failed) or schedules
   * the next attempt after the backoff delay. Called with the lock held; the delayed task re-checks
   * that a connection is still wanted and that nothing else has moved the state on before starting
   * the attempt.
   */
  private void scheduleReconnect(String reason) {
    attempts++;
    if (settings.reconnect().exhausted(attempts)) {
      setState(GatewayState.ERROR, "重连次数用尽：" + reason);
      CompletableFuture<Void> pending = ready;
      if (pending != null && !pending.isDone()) {
        pending.completeExceptionally(
            new GatewayException(broker, 0, name + " 重连次数用尽：" + reason, false));
      }
      return;
    }
    Duration delay = settings.reconnect().delayFor(attempts, random);
    setState(
        GatewayState.RECONNECTING,
        reason
            + "；"
            + String.format("%.1f", delay.toMillis() / 1000.0)
            + " 秒后第 "
            + attempts
            + " 次重连");
    reconnectTask =
        scheduler.schedule(
            () -> {
              synchronized (lock) {
                reconnectTask = null;
                if (wantConnected && state == GatewayState.RECONNECTING) {
                  startAttempt();
                }
              }
            },
            delay.toMillis(),
            TimeUnit.MILLISECONDS);
  }

  // ------------------------------------------------------------------ internal: heartbeat

  private void scheduleHeartbeat() {
    long interval = settings.heartbeatInterval().toMillis();
    heartbeatTask =
        scheduler.scheduleWithFixedDelay(
            this::heartbeat, interval, interval, TimeUnit.MILLISECONDS);
  }

  /**
   * Periodic heartbeat on the scheduler thread. The probe is issued outside the lock (it talks to
   * the SDK) and is bounded by {@code heartbeatTimeout}; its result is matched to the generation it
   * was sent for.
   */
  private void heartbeat() {
    int gen;
    synchronized (lock) {
      if (state != GatewayState.CONNECTED) {
        return;
      }
      gen = generation;
    }
    CompletableFuture<Boolean> probe;
    try {
      probe = transport.probe();
    } catch (RuntimeException e) {
      probe = CompletableFuture.failedFuture(e);
    }
    final CompletableFuture<Boolean> probing = probe;
    ScheduledFuture<?> timeout =
        scheduler.schedule(
            () -> probing.completeExceptionally(new RequestTimeoutException(broker, name + " 心跳")),
            settings.heartbeatTimeout().toMillis(),
            TimeUnit.MILLISECONDS);
    probing.whenCompleteAsync(
        (ok, ex) -> {
          timeout.cancel(false);
          onProbeResult(gen, ex == null && Boolean.TRUE.equals(ok), ex);
        },
        scheduler);
  }

  private void onProbeResult(int gen, boolean ok, Throwable ex) {
    String reason;
    synchronized (lock) {
      if (gen != generation || state != GatewayState.CONNECTED) {
        return;
      }
      if (ok) {
        heartbeatFailures = 0;
        lastHeartbeatAt = clock.instant();
        return;
      }
      heartbeatFailures++;
      log.warn(
          "{} 心跳失败 {}/{}：{}",
          name,
          heartbeatFailures,
          settings.heartbeatFailuresBeforeReconnect(),
          ex == null ? "对端无应答" : describe(ex));
      if (heartbeatFailures < settings.heartbeatFailuresBeforeReconnect()) {
        return;
      }
      reason = "心跳连续 " + heartbeatFailures + " 次失败";
    }
    handleTransportClosed(reason); // already on the scheduler thread
  }

  // ------------------------------------------------------------------ internal: misc

  private void setState(GatewayState next, String why) {
    if (state != next) {
      log.info("{} 状态 {} → {}：{}", name, state, next, why);
    }
    state = next;
    detail = why;
    if (next != GatewayState.CONNECTED) {
      connectedSince = null;
    }
  }

  private void cancelTasks() {
    if (heartbeatTask != null) {
      heartbeatTask.cancel(false);
      heartbeatTask = null;
    }
    if (reconnectTask != null) {
      reconnectTask.cancel(false);
      reconnectTask = null;
    }
  }

  private void safeClose() {
    try {
      transport.close();
    } catch (RuntimeException e) {
      log.warn("{} 关闭传输层时出错：{}", name, e.toString());
    }
  }

  private void submit(Runnable task) {
    try {
      scheduler.execute(task);
    } catch (RejectedExecutionException e) {
      log.debug("{} 调度线程已关闭，忽略传输层事件", name);
    }
  }

  private void notifyDisconnected(String reason) {
    dispatch(l -> l.onDisconnected(broker, reason));
  }

  /**
   * Notifies every listener in one scheduler task, outside the lock. A listener that throws is
   * logged and does not stop the others.
   */
  private void dispatch(Consumer<GatewayListener> action) {
    scheduler.execute(
        () -> {
          for (GatewayListener l : listeners) {
            try {
              action.accept(l);
            } catch (RuntimeException e) {
              log.warn("{} 监听器抛出异常：{}", name, e.toString());
            }
          }
        });
  }

  private static String describe(Throwable t) {
    Throwable c = t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
    String msg = c.getMessage();
    return msg == null || msg.isBlank() ? c.getClass().getSimpleName() : msg;
  }
}
