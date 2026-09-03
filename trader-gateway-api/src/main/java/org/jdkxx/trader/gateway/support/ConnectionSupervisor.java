package org.jdkxx.trader.gateway.support;

import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.gateway.GatewayException;
import org.jdkxx.trader.gateway.GatewayListener;
import org.jdkxx.trader.gateway.GatewayState;
import org.jdkxx.trader.gateway.GatewayStatus;
import org.jdkxx.trader.gateway.RequestTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 与 SDK 无关的连接状态机：连接、就绪、心跳、断线检测、指数退避重连、主动断开。
 *
 * <pre>
 * DISCONNECTED ──connect()──▶ CONNECTING ──就绪──▶ CONNECTED
 *      ▲                          │失败                │ 传输层报断线 / 心跳连续失败
 *      │                          ▼                    ▼
 *      └───disconnect()──── RECONNECTING ◀─────────────┘
 * </pre>
 *
 * <p>线程模型：状态在 {@code lock} 内变更；Transport 返回的 Future 一律用 {@code whenCompleteAsync(scheduler)}
 * 接回调度线程，SDK 的回调线程永远不会执行状态机或监听器；监听器通知也在调度线程上、锁外进行。
 * {@code generation} 用来丢弃过期的异步结果（例如 disconnect 后才完成的旧连接）。
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

    public ConnectionSupervisor(Broker broker, String name, Transport transport, SupervisorSettings settings,
                                ScheduledExecutorService scheduler) {
        this(broker, name, transport, settings, scheduler, Clock.systemUTC(), new Random());
    }

    public ConnectionSupervisor(Broker broker, String name, Transport transport, SupervisorSettings settings,
                                ScheduledExecutorService scheduler, Clock clock, Random random) {
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

    // ------------------------------------------------------------------ 对外操作

    public CompletableFuture<Void> connect() {
        synchronized (lock) {
            wantConnected = true;
            if (state == GatewayState.CONNECTED) {
                return CompletableFuture.completedFuture(null);
            }
            if (ready == null || ready.isDone()) {
                ready = new CompletableFuture<>();
            }
            if (state == GatewayState.CONNECTING || (state == GatewayState.RECONNECTING && reconnectTask != null)) {
                return ready;   // 已在进行中
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

    /** 不可重试的错误（配置非法、账户不在受管列表）：停止一切并进入 ERROR。 */
    public void reportFatal(GatewayException error) {
        CompletableFuture<Void> pending;
        synchronized (lock) {
            wantConnected = false;
            generation++;
            cancelTasks();
            safeClose();
            setState(GatewayState.ERROR, error.getMessage());
            pending = ready;
        }
        if (pending != null && !pending.isDone()) {
            pending.completeExceptionally(error);
        }
        dispatch(l -> l.onError(broker, error));
    }

    /** 传输层报告连接已断（对端关闭、读线程退出）。 */
    public void onTransportClosed(String reason) {
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

    /** 连接仍在但券商侧数据丢失（盈透 1101）：等价于一次重连，让上层重订阅。 */
    public void notifyDataLost(String reason) {
        synchronized (lock) {
            if (state != GatewayState.CONNECTED) {
                return;
            }
            detail = "已连接（" + reason + "）";
        }
        dispatch(l -> l.onConnected(broker, true));
    }

    // ------------------------------------------------------------------ 查询

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
            return new GatewayStatus(state, detail, clock.instant(), connectedSince, lastHeartbeatAt, attempts, facts);
        }
    }

    public String name() {
        return name;
    }

    // ------------------------------------------------------------------ 内部：连接尝试

    private void startAttempt() {
        int gen = ++generation;
        setState(everConnected || attempts > 0 ? GatewayState.RECONNECTING : GatewayState.CONNECTING,
                attempts == 0 ? "连接中" : "第 " + attempts + " 次重连中");
        CompletableFuture<Void> open;
        try {
            open = transport.open();
        } catch (RuntimeException e) {
            open = CompletableFuture.failedFuture(e);
        }
        final CompletableFuture<Void> opening = open;
        ScheduledFuture<?> timeout = scheduler.schedule(
                () -> opening.completeExceptionally(new RequestTimeoutException(broker, name + " 建连")),
                settings.connectTimeout().toMillis(), TimeUnit.MILLISECONDS);
        opening.whenCompleteAsync((v, ex) -> {
            timeout.cancel(false);
            onOpenResult(gen, ex);
        }, scheduler);
    }

    private void onOpenResult(int gen, Throwable ex) {
        boolean reconnected;
        CompletableFuture<Void> pending;
        synchronized (lock) {
            if (gen != generation) {
                return;   // 过期结果：期间已 disconnect 或重新发起
            }
            if (!wantConnected) {
                safeClose();
                return;
            }
            if (ex != null) {
                safeClose();
                scheduleReconnect(describe(ex));
                return;
            }
            reconnected = everConnected;
            everConnected = true;
            attempts = 0;
            heartbeatFailures = 0;
            connectedSince = clock.instant();
            lastHeartbeatAt = connectedSince;
            setState(GatewayState.CONNECTED, reconnected ? "已重连" : "已连接");
            scheduleHeartbeat();
            pending = ready;
        }
        if (pending != null) {
            pending.complete(null);
        }
        dispatch(l -> l.onConnected(broker, reconnected));
    }

    private void scheduleReconnect(String reason) {
        attempts++;
        if (settings.reconnect().exhausted(attempts)) {
            setState(GatewayState.ERROR, "重连次数用尽：" + reason);
            CompletableFuture<Void> pending = ready;
            if (pending != null && !pending.isDone()) {
                pending.completeExceptionally(new GatewayException(broker, 0, name + " 重连次数用尽：" + reason, false));
            }
            return;
        }
        Duration delay = settings.reconnect().delayFor(attempts, random);
        setState(GatewayState.RECONNECTING, reason + "；" + String.format("%.1f", delay.toMillis() / 1000.0) + " 秒后第 " + attempts + " 次重连");
        reconnectTask = scheduler.schedule(() -> {
            synchronized (lock) {
                reconnectTask = null;
                if (wantConnected && state == GatewayState.RECONNECTING) {
                    startAttempt();
                }
            }
        }, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    // ------------------------------------------------------------------ 内部：心跳

    private void scheduleHeartbeat() {
        long interval = settings.heartbeatInterval().toMillis();
        heartbeatTask = scheduler.scheduleWithFixedDelay(this::heartbeat, interval, interval, TimeUnit.MILLISECONDS);
    }

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
        ScheduledFuture<?> timeout = scheduler.schedule(
                () -> probing.completeExceptionally(new RequestTimeoutException(broker, name + " 心跳")),
                settings.heartbeatTimeout().toMillis(), TimeUnit.MILLISECONDS);
        probing.whenCompleteAsync((ok, ex) -> {
            timeout.cancel(false);
            onProbeResult(gen, ex == null && Boolean.TRUE.equals(ok), ex);
        }, scheduler);
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
            log.warn("{} 心跳失败 {}/{}：{}", name, heartbeatFailures, settings.heartbeatFailuresBeforeReconnect(),
                    ex == null ? "对端无应答" : describe(ex));
            if (heartbeatFailures < settings.heartbeatFailuresBeforeReconnect()) {
                return;
            }
            reason = "心跳连续 " + heartbeatFailures + " 次失败";
        }
        onTransportClosed(reason);
    }

    // ------------------------------------------------------------------ 内部：杂项

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

    private void notifyDisconnected(String reason) {
        dispatch(l -> l.onDisconnected(broker, reason));
    }

    private void dispatch(Consumer<GatewayListener> action) {
        scheduler.execute(() -> {
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
        Throwable c = t instanceof java.util.concurrent.CompletionException && t.getCause() != null ? t.getCause() : t;
        String msg = c.getMessage();
        return msg == null || msg.isBlank() ? c.getClass().getSimpleName() : msg;
    }
}
