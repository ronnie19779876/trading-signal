package org.jdkxx.trader.gateway.ibkr;

import org.jdkxx.trader.gateway.GatewayState;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * 握手无应答：端口在监听、连接能建立，但对端一个字节都不回（autossh 隧道本地端口还在、到服务器的链路已断）。
 *
 * <p>2.0.2 前：SDK 的 eConnect 是 synchronized 且读服务器版本号没有超时，一直占着 client 的对象锁；
 * 建连超时后状态机在自己的锁里调 close()→isConnected() 等这把锁，调度线程永久挂起，
 * 状态查询、主动断开、应用关停全部卡死。
 */
class IbkrHandshakeTimeoutTest {

    @Test
    void 握手无应答时状态查询与主动断开不被卡住() {
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            try (ServerSocket blackhole = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
                List<Socket> accepted = new CopyOnWriteArrayList<>();
                Thread acceptor = new Thread(() -> {
                    while (!blackhole.isClosed()) {
                        try {
                            accepted.add(blackhole.accept());   // 接受但从不读写
                        } catch (IOException e) {
                            return;
                        }
                    }
                }, "blackhole-acceptor");
                acceptor.setDaemon(true);
                acceptor.start();

                IbkrProperties d = IbkrProperties.disabled();
                IbkrProperties props = new IbkrProperties(true, true, "127.0.0.1", blackhole.getLocalPort(), 12, null, "NASDAQ",
                        Duration.ofMillis(500), d.requestTimeout(), d.heartbeatInterval(), d.heartbeatTimeout(), 40, d.reconnect());
                IbkrGateway gateway = new IbkrGateway(props);
                ExecutorService caller = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "test-caller");
                    t.setDaemon(true);
                    return t;
                });
                try {
                    gateway.connect();
                    Thread.sleep(1_500);   // 建连超时（500ms）已过
                    assertThat(accepted).as("连接确实建立到了黑洞端口").isNotEmpty();

                    Future<GatewayState> state = caller.submit(() -> gateway.status().state());
                    assertThat(state.get(2, TimeUnit.SECONDS)).as("状态查询立即返回").isIn(GatewayState.RECONNECTING, GatewayState.CONNECTING);

                    caller.submit(gateway::disconnect).get(2, TimeUnit.SECONDS);
                    assertThat(caller.submit(() -> gateway.status().state()).get(2, TimeUnit.SECONDS)).isEqualTo(GatewayState.DISCONNECTED);
                } finally {
                    caller.shutdownNow();
                    for (Socket s : accepted) {
                        s.close();
                    }
                }
                caller.shutdown();
                Executors.newSingleThreadExecutor().submit(gateway::close).get(5, TimeUnit.SECONDS);
            }
        });
    }
}
