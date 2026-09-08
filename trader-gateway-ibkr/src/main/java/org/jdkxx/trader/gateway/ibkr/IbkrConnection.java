package org.jdkxx.trader.gateway.ibkr;

import com.ib.client.Contract;
import com.ib.client.ContractDetails;
import com.ib.client.EClientSocket;
import com.ib.client.EJavaSignal;
import com.ib.client.EReader;
import org.jdkxx.trader.common.ratelimit.RateLimiter;
import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.gateway.GatewayException;
import org.jdkxx.trader.gateway.NotConnectedException;
import org.jdkxx.trader.gateway.support.Transport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 一条到 IB Gateway / TWS 的 socket 会话及其线程：
 * <ul>
 *   <li>{@code ibkr-connect}：跑同步握手的 eConnect（几秒内返回，由 supervisor 的 connect-timeout 兜底）</li>
 *   <li>{@code ibkr-reader}：SDK 的 EReader，把报文切进队列</li>
 *   <li>{@code ibkr-pump}：循环 {@code waitForSignal / processMsgs}，所有 EWrapper 回调在这条线程上</li>
 * </ul>
 * 每次 open() 新建一套（EClientSocket 不复用）；就绪信号是 nextValidId。
 */
final class IbkrConnection implements Transport, IbkrWrapper.ConnectionEvents {

    private static final Logger log = LoggerFactory.getLogger(IbkrConnection.class);

    private final IbkrProperties props;
    private final IbkrRequestRegistry registry;
    private final IbkrFacts facts;
    private final Executor dispatch;
    private final RateLimiter limiter;
    private final IbkrWrapper wrapper;
    private final ExecutorService connectExecutor;
    private final RepeatSuppressor errorLog = new RepeatSuppressor(Duration.ofMinutes(10), Clock.systemUTC());
    private final ConcurrentLinkedQueue<CompletableFuture<Instant>> timeWaiters = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<CompletableFuture<List<String>>> accountWaiters = new ConcurrentLinkedQueue<>();

    private volatile Consumer<String> closedHandler = reason -> { };
    private volatile Consumer<String> dataLostHandler = reason -> { };
    private volatile Session session;

    private static final class Session {
        final EJavaSignal signal = new EJavaSignal();
        final CompletableFuture<Void> ready = new CompletableFuture<>();
        final AtomicBoolean closed = new AtomicBoolean();
        volatile EClientSocket client;
        volatile EReader reader;
        volatile Thread pump;
        volatile boolean intentional;
    }

    IbkrConnection(IbkrProperties props, IbkrRequestRegistry registry, IbkrFacts facts, Executor dispatch,
                   RateLimiter limiter) {
        this.props = props;
        this.registry = registry;
        this.facts = facts;
        this.dispatch = dispatch;
        this.limiter = limiter;
        this.wrapper = new IbkrWrapper(this, registry);
        this.connectExecutor = Executors.newSingleThreadExecutor(r -> {
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
        Session s = new Session();
        s.client = new EClientSocket(wrapper, s.signal);
        session = s;
        facts.reset();
        connectExecutor.execute(() -> {
            try {
                s.client.eConnect(props.host(), props.port(), props.clientId());
                if (!s.client.isConnected()) {
                    s.ready.completeExceptionally(new GatewayException(Broker.IBKR, 502,
                            "连不上 IB Gateway / TWS：确认网关在运行、端口与隧道正确、API 设置里允许了本机连接", true));
                    return;
                }
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
                s.ready.completeExceptionally(new GatewayException(Broker.IBKR, 0, "建连失败：" + t, true, t));
                closeSession(s);
            }
        });
        return s.ready;
    }

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
        closeSession(s);
    }

    private void closeSession(Session s) {
        try {
            if (s.client != null && s.client.isConnected()) {
                s.client.eDisconnect();
            }
        } catch (RuntimeException e) {
            log.warn("eDisconnect 出错：{}", e.toString());
        }
        s.signal.issueSignal();
        sessionEnded(s, s.intentional ? "主动断开" : "连接已关闭");
    }

    private void sessionEnded(Session s, String reason) {
        if (!s.closed.compareAndSet(false, true)) {
            return;
        }
        NotConnectedException gone = new NotConnectedException(Broker.IBKR, reason);
        registry.failAll(gone);
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
        return currentTime().thenApply(t -> true);
    }

    // ------------------------------------------------------------------ 请求

    boolean isConnected() {
        Session s = session;
        return s != null && s.ready.isDone() && !s.ready.isCompletedExceptionally() && !s.closed.get()
                && s.client.isConnected();
    }

    CompletableFuture<Instant> currentTime() {
        CompletableFuture<Instant> f = new CompletableFuture<>();
        if (!send(EClientSocket::reqCurrentTime, f)) {
            return f;
        }
        timeWaiters.add(f);
        return f;
    }

    CompletableFuture<List<String>> managedAccounts() {
        List<String> known = facts.managedAccounts();
        if (!known.isEmpty()) {
            return CompletableFuture.completedFuture(known);
        }
        CompletableFuture<List<String>> f = new CompletableFuture<>();
        if (!send(EClientSocket::reqManagedAccts, f)) {
            return f;
        }
        accountWaiters.add(f);
        return f;
    }

    CompletableFuture<List<ContractDetails>> contractDetails(Contract contract) {
        int id = registry.nextId();
        CompletableFuture<List<ContractDetails>> f = registry.open(id, "合约查询 " + contract.symbol(),
                new PendingRequest.Many<>(ContractDetails.class), null);
        Session s = session;
        if (!isConnected()) {
            registry.fail(id, new NotConnectedException(Broker.IBKR, "尚未连接"));
            return f;
        }
        try {
            limiter.acquire();
            s.client.reqContractDetails(id, contract);
        } catch (RuntimeException e) {
            registry.fail(id, e);
        }
        return f;
    }

    /** 发送一个无 reqId 的请求；未连接或发送失败时让 future 异常完成并返回 false。 */
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

    // ------------------------------------------------------------------ ConnectionEvents（泵线程）

    @Override
    public void onConnectAck() {
        log.debug("TWS connectAck");
    }

    @Override
    public void onNextValidId(int orderId) {
        facts.nextOrderId(orderId);
        Session s = session;
        if (s != null && !s.ready.isDone()) {
            errorLog.reset();
            s.ready.complete(null);
        }
    }

    @Override
    public void onManagedAccounts(String accounts) {
        List<String> list = Arrays.stream(accounts.split(","))
                .map(String::trim)
                .filter(a -> !a.isEmpty())
                .toList();
        facts.managedAccounts(list);
        CompletableFuture<List<String>> w;
        while ((w = accountWaiters.poll()) != null) {
            CompletableFuture<List<String>> f = w;
            dispatch.execute(() -> f.complete(list));
        }
    }

    @Override
    public void onCurrentTime(long epochSeconds) {
        CompletableFuture<Instant> w = timeWaiters.poll();
        if (w != null) {
            dispatch.execute(() -> w.complete(Instant.ofEpochSecond(epochSeconds)));
        }
    }

    @Override
    public void onConnectionClosed() {
        Session s = session;
        if (s != null) {
            sessionEnded(s, "网关关闭了连接（每日重启 / 对端退出 / client-id 冲突）");
        }
    }

    @Override
    public void onSystemMessage(int code, String message) {
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
                        log.warn("TWS 错误 {} 持续中（已 {} 分钟，期间又出现 {} 次）：{}",
                                code, d.since().toMinutes(), d.suppressed(), message);
                    }
                }
            }
        }
    }

    private void farm(String message, String state) {
        int i = message == null ? -1 : message.lastIndexOf(':');
        String name = i >= 0 && i < message.length() - 1 ? message.substring(i + 1).trim() : "?";
        facts.farm(name, state);
    }
}
