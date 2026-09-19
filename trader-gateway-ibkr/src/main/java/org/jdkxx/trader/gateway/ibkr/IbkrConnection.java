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
import org.jdkxx.trader.gateway.ibkr.mapper.IbkrAccounts;
import org.jdkxx.trader.gateway.support.Transport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

/**
 * 一条到 IB Gateway / TWS 的 socket 会话及其线程：
 * <ul>
 *   <li>{@code ibkr-connect}：建 socket（带连接超时）并跑同步握手的 eConnect（握手期带读超时）</li>
 *   <li>{@code ibkr-reader}：SDK 的 EReader，把报文切进队列</li>
 *   <li>{@code ibkr-pump}：循环 {@code waitForSignal / processMsgs}，所有 EWrapper 回调在这条线程上</li>
 * </ul>
 * 每次 open() 新建一套（EClientSocket 与 EWrapper 都不复用），回调只作用于产生它的会话；就绪信号是 nextValidId。
 *
 * <p>锁纪律：EClientSocket 的 eConnect / isConnected / eDisconnect 都是 synchronized（10.30.01 javap 核实），
 * 握手卡住时 eConnect 一直占着这把锁。状态机会在自己的锁里调 open() / close()，所以这两个方法不碰 client 的锁：
 * 先关底层 socket 把卡住的读顶出来，eDisconnect 放到 dispatch 线程上做。
 * 2.0.2 前 close() 里直接调 isConnected()，握手无应答时状态机的调度线程被永久挂起。
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
        volatile Socket socket;
        volatile EReader reader;
        volatile Thread pump;
        volatile boolean intentional;
    }

    IbkrConnection(IbkrProperties props, IbkrRequestRegistry registry, IbkrSubscriptions subscriptions, IbkrFacts facts,
                   Executor dispatch, RateLimiter limiter) {
        this.props = props;
        this.registry = registry;
        this.subscriptions = subscriptions;
        this.facts = facts;
        this.dispatch = dispatch;
        this.limiter = limiter;
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
        Session old = session;
        if (old != null) {
            // 先结束旧会话：它的在途请求在新会话有请求之前就失败掉，旧 socket 也不会留着占 client-id
            old.intentional = true;
            closeSession(old, "被新的连接取代");
        }
        Session s = new Session();
        s.client = new EClientSocket(new IbkrWrapper(new SessionEvents(s), registry, subscriptions), s.signal);
        session = s;
        facts.reset();
        connectExecutor.execute(() -> handshake(s));
        return s.ready;
    }

    private void handshake(Session s) {
        int timeoutMs = (int) Math.max(1, props.connectTimeout().toMillis());
        Socket socket = new Socket();
        s.socket = socket;
        try {
            if (s.closed.get()) {
                closeQuietly(socket);
                return;
            }
            socket.connect(new InetSocketAddress(props.host(), props.port()), timeoutMs);
            // 握手期读超时：端口在监听、对端不应答时（隧道本地端口还在、链路已断），SDK 读服务器版本号不会无限阻塞
            socket.setSoTimeout(timeoutMs);
            s.client.eConnect(socket, props.clientId());
            if (!s.client.isConnected()) {
                s.ready.completeExceptionally(new GatewayException(Broker.IBKR, 502,
                        "连不上 IB Gateway / TWS：确认网关在运行、端口与隧道正确、API 设置里允许了本机连接", true));
                closeSession(s, "握手失败");
                return;
            }
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
            s.ready.completeExceptionally(new GatewayException(Broker.IBKR, 502, "建连失败：" + describeIo(t), true, t));
            closeSession(s, "建连失败");
        }
    }

    /** 不带异常原文：UnknownHostException 的消息就是主机名，而主机属于敏感配置。 */
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

    /** 不碰 client 的对象锁（见类注释）；可以在任何线程、任何锁里调用。 */
    private void closeSession(Session s, String reason) {
        Socket socket = s.socket;
        if (socket != null) {
            closeQuietly(socket);   // 卡在握手读上的 eConnect 立即返回，client 的锁随之释放
        }
        s.signal.issueSignal();
        sessionEnded(s, reason);
        Runnable disconnect = () -> {
            try {
                s.client.eDisconnect();
            } catch (RuntimeException e) {
                log.debug("eDisconnect 出错：{}", e.toString());
            }
        };
        try {
            dispatch.execute(disconnect);
        } catch (RejectedExecutionException e) {
            disconnect.run();   // 关停中：socket 已关，这里不会久等
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException e) {
            // ignore
        }
    }

    private void sessionEnded(Session s, String reason) {
        if (!s.closed.compareAndSet(false, true)) {
            return;
        }
        NotConnectedException gone = new NotConnectedException(Broker.IBKR, reason);
        registry.failAll(gone);
        subscriptions.clear();   // 订阅随会话失效；订阅方在重连后用新 reqId 重订
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
        // 心跳超时由状态机让 probe 失败；原等待者必须出队，否则之后每个应答都配给上一个已作废的等待者，心跳一直失败
        probe.whenComplete((ok, ex) -> {
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

    // ------------------------------------------------------------------ 请求

    boolean isConnected() {
        Session s = session;
        return s != null && s.ready.isDone() && !s.ready.isCompletedExceptionally() && !s.closed.get()
                && s.client.isConnected();
    }

    CompletableFuture<Instant> currentTime() {
        CompletableFuture<Instant> f = new CompletableFuture<>();
        timeWaiters.add(f);   // 先入队再发：应答可能先于入队到达
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
        CompletableFuture<List<ContractDetails>> f = registry.open(id, "合约查询 " + contract.symbol(),
                new PendingRequest.Many<>(ContractDetails.class), null);
        sendWithId(id, c -> c.reqContractDetails(id, contract));
        return f;
    }

    /**
     * 持仓（reqPositionsMulti）。它是订阅式请求：End 之后券商仍会推送变动，
     * 所以 future 一结束（收齐、失败、超时都算）就取消。取消跟着 future 在 dispatch 线程上发，不占泵线程。
     */
    CompletableFuture<List<IbkrAccounts.PositionRow>> positions(String account) {
        int id = registry.nextId();
        CompletableFuture<List<IbkrAccounts.PositionRow>> f = registry.open(id, "持仓查询",
                new PendingRequest.Many<>(IbkrAccounts.PositionRow.class), null);
        f.whenComplete((rows, ex) -> cancelQuietly("持仓查询", c -> c.cancelPositionsMulti(id)));
        sendWithId(id, c -> c.reqPositionsMulti(id, account, ""));
        return f;
    }

    /**
     * 账户汇总（reqAccountSummary，组 All，由调用方按账户筛）。同样是订阅式：结束即取消。
     * 网关对账户汇总订阅有全局并发上限，并发合并在 {@link IbkrGateway} 里做。
     */
    CompletableFuture<List<IbkrAccounts.SummaryRow>> accountSummary(String tags) {
        int id = registry.nextId();
        CompletableFuture<List<IbkrAccounts.SummaryRow>> f = registry.open(id, "账户汇总",
                new PendingRequest.Many<>(IbkrAccounts.SummaryRow.class), null);
        f.whenComplete((rows, ex) -> cancelQuietly("账户汇总", c -> c.cancelAccountSummary(id)));
        sendWithId(id, c -> c.reqAccountSummary(id, "All", tags));
        return f;
    }

    // ------------------------------------------------------------------ 常驻订阅（实时账户）

    /** 当前会话的标识：订阅方据此判断旧 reqId 是否还活着（1101 数据丢失时会话不变，要先取消再重订）。 */
    Object sessionToken() {
        return session;
    }

    int nextId() {
        return registry.nextId();
    }

    /** 发一个常驻订阅。未连接或发送失败返回 false，由订阅方等重连后再订。 */
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

    /** 发送一个带 reqId 的请求；未连接或发送失败时让注册表里的请求失败。 */
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

    /** 取消订阅式请求。连接已断就不必取消（订阅随会话结束）；取消失败只记日志。 */
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

    // ------------------------------------------------------------------ 连接级回调（泵线程）

    /** 绑定到一个会话的回调：旧会话迟到的 nextValidId / 断线不会作用到新会话上。 */
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
