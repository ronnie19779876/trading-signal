package org.jdkxx.trader.gateway.support;

import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.gateway.GatewayException;
import org.jdkxx.trader.gateway.GatewayListener;
import org.jdkxx.trader.gateway.GatewayState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionSupervisorTest {

    /** 可控的传输层：每次 open/probe 返回一个由测试完成的 Future。 */
    private static final class FakeTransport implements Transport {
        final List<CompletableFuture<Void>> opens = new ArrayList<>();
        final List<CompletableFuture<Boolean>> probes = new ArrayList<>();
        int closes;
        boolean open = true;

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public CompletableFuture<Void> open() {
            CompletableFuture<Void> f = new CompletableFuture<>();
            opens.add(f);
            return f;
        }

        @Override
        public void close() {
            closes++;
        }

        @Override
        public CompletableFuture<Boolean> probe() {
            CompletableFuture<Boolean> f = new CompletableFuture<>();
            probes.add(f);
            return f;
        }
    }

    private final ManualScheduler scheduler = new ManualScheduler();
    private final FakeTransport transport = new FakeTransport();
    private final List<String> events = new ArrayList<>();
    private ConnectionSupervisor supervisor;

    @BeforeEach
    void setUp() {
        SupervisorSettings settings = new SupervisorSettings(Duration.ofSeconds(10), Duration.ofSeconds(30),
                Duration.ofSeconds(10), 2, new ReconnectPolicy(Duration.ofSeconds(5), Duration.ofSeconds(60), -1, 0));
        supervisor = new ConnectionSupervisor(Broker.IBKR, "test", transport, settings, scheduler,
                Clock.fixed(Instant.parse("2026-09-03T00:00:00Z"), ZoneOffset.UTC), new Random(1));
        supervisor.addListener(new GatewayListener() {
            @Override
            public void onConnected(Broker broker, boolean reconnected) {
                events.add(reconnected ? "reconnected" : "connected");
            }

            @Override
            public void onDisconnected(Broker broker, String reason) {
                events.add("disconnected");
            }

            @Override
            public void onError(Broker broker, GatewayException error) {
                events.add("error");
            }
        });
    }

    @Test
    void 连接成功进入CONNECTED并通知监听器() {
        CompletableFuture<Void> ready = supervisor.connect();
        assertThat(supervisor.state()).isEqualTo(GatewayState.CONNECTING);

        transport.opens.get(0).complete(null);
        scheduler.runPending();

        assertThat(supervisor.state()).isEqualTo(GatewayState.CONNECTED);
        assertThat(ready).isCompleted();
        assertThat(events).containsExactly("connected");
        assertThat(supervisor.status(Map.of()).connectedSince()).isNotNull();
    }

    @Test
    void 首次连接失败按退避重试且重试成功后不算重连() {
        supervisor.connect();
        transport.opens.get(0).completeExceptionally(new RuntimeException("502"));
        scheduler.runPending();

        assertThat(supervisor.state()).isEqualTo(GatewayState.RECONNECTING);
        assertThat(supervisor.status(Map.of()).reconnectAttempts()).isEqualTo(1);
        assertThat(transport.opens).hasSize(1);

        scheduler.tick(Duration.ofSeconds(5));
        assertThat(transport.opens).hasSize(2);
        transport.opens.get(1).complete(null);
        scheduler.runPending();

        assertThat(supervisor.state()).isEqualTo(GatewayState.CONNECTED);
        assertThat(events).containsExactly("connected");
    }

    @Test
    void 建连超时也进入重连() {
        supervisor.connect();
        scheduler.tick(Duration.ofSeconds(10));
        assertThat(supervisor.state()).isEqualTo(GatewayState.RECONNECTING);
        assertThat(transport.closes).isEqualTo(1);
    }

    @Test
    void 断线后重连成功标记为reconnected() {
        supervisor.connect();
        transport.opens.get(0).complete(null);
        scheduler.runPending();

        supervisor.onTransportClosed("对端关闭");
        scheduler.runPending();
        assertThat(supervisor.state()).isEqualTo(GatewayState.RECONNECTING);
        assertThat(events).containsExactly("connected", "disconnected");

        scheduler.tick(Duration.ofSeconds(5));
        transport.opens.get(1).complete(null);
        scheduler.runPending();
        assertThat(supervisor.state()).isEqualTo(GatewayState.CONNECTED);
        assertThat(events).containsExactly("connected", "disconnected", "reconnected");
        assertThat(supervisor.status(Map.of()).reconnectAttempts()).isZero();
    }

    @Test
    void 心跳连续失败两次触发重连一次失败不触发() {
        supervisor.connect();
        transport.opens.get(0).complete(null);
        scheduler.runPending();

        scheduler.tick(Duration.ofSeconds(30));          // 第 1 次心跳
        assertThat(transport.probes).hasSize(1);
        transport.probes.get(0).complete(true);
        scheduler.runPending();
        assertThat(supervisor.status(Map.of()).lastHeartbeatAt()).isNotNull();

        scheduler.tick(Duration.ofSeconds(30));          // 第 2 次：超时
        scheduler.tick(Duration.ofSeconds(10));
        assertThat(supervisor.state()).isEqualTo(GatewayState.CONNECTED);

        scheduler.tick(Duration.ofSeconds(20));          // 第 3 次：再次超时 → 断开重连
        scheduler.tick(Duration.ofSeconds(10));
        assertThat(supervisor.state()).isEqualTo(GatewayState.RECONNECTING);
        assertThat(events).containsExactly("connected", "disconnected");
    }

    @Test
    void 主动断开停止重连并让未完成的连接异常结束() {
        CompletableFuture<Void> ready = supervisor.connect();
        supervisor.disconnect();
        assertThat(supervisor.state()).isEqualTo(GatewayState.DISCONNECTED);
        assertThat(ready).isCompletedExceptionally();

        // 旧连接迟到的成功结果被丢弃
        transport.opens.get(0).complete(null);
        scheduler.tick(Duration.ofMinutes(5));
        assertThat(supervisor.state()).isEqualTo(GatewayState.DISCONNECTED);
        assertThat(transport.opens).hasSize(1);
    }

    @Test
    void 重连尝试进行中再调connect不重复建连() {
        // 2.0.2 前：RECONNECTING 且尝试正在握手（reconnectTask 已清空）时 connect() 会再发起一次，
        // 同一个 client-id 连两次互踢，旧尝试的 socket 也没人关
        supervisor.connect();
        transport.opens.get(0).completeExceptionally(new RuntimeException("502"));
        scheduler.runPending();
        scheduler.tick(Duration.ofSeconds(5));
        assertThat(transport.opens).hasSize(2);
        assertThat(supervisor.state()).isEqualTo(GatewayState.RECONNECTING);

        CompletableFuture<Void> ready = supervisor.connect();

        assertThat(transport.opens).hasSize(2);
        transport.opens.get(1).complete(null);
        scheduler.runPending();
        assertThat(ready).isCompleted();
    }

    @Test
    void 传输层断线回调转到调度线程处理() {
        // SDK 回调线程拿着它自己的锁调进来；在这条线程上抢状态机的锁，与"状态机持锁调 SDK"加锁顺序相反会死锁
        supervisor.connect();
        transport.opens.get(0).complete(null);
        scheduler.runPending();

        supervisor.onTransportClosed("对端关闭");
        assertThat(supervisor.state()).as("调用线程上不改状态").isEqualTo(GatewayState.CONNECTED);

        scheduler.runPending();
        assertThat(supervisor.state()).isEqualTo(GatewayState.RECONNECTING);
    }

    @Test
    void 就绪后传输层已断开的不进入CONNECTED() {
        supervisor.connect();
        transport.open = false;                           // 握手刚完成就被网关断开
        transport.opens.get(0).complete(null);
        scheduler.runPending();

        assertThat(supervisor.state()).isEqualTo(GatewayState.RECONNECTING);
        assertThat(events).isEmpty();
    }

    @Test
    void 不可重试的建连错误进入ERROR不再重连() {
        CompletableFuture<Void> ready = supervisor.connect();
        transport.opens.get(0).completeExceptionally(new GatewayException(Broker.FUTU, 0, "读取 RSA 私钥失败", false));
        scheduler.runPending();

        assertThat(supervisor.state()).isEqualTo(GatewayState.ERROR);
        assertThat(ready).isCompletedExceptionally();
        assertThat(events).containsExactly("error");
        scheduler.tick(Duration.ofMinutes(5));
        assertThat(transport.opens).hasSize(1);
    }

    @Test
    void 已连接时的致命错误补发断开通知() {
        supervisor.connect();
        transport.opens.get(0).complete(null);
        scheduler.runPending();

        supervisor.reportFatal(new GatewayException(Broker.IBKR, 0, "账户不在受管列表", false));
        scheduler.runPending();

        assertThat(events).containsExactly("connected", "disconnected", "error");
    }

    @Test
    void 致命错误进入ERROR并通知() {
        supervisor.connect();
        supervisor.reportFatal(new GatewayException(Broker.IBKR, 0, "账户不在受管列表", false));
        scheduler.runPending();
        assertThat(supervisor.state()).isEqualTo(GatewayState.ERROR);
        assertThat(events).containsExactly("error");
        scheduler.tick(Duration.ofMinutes(5));
        assertThat(transport.opens).hasSize(1);
    }
}
