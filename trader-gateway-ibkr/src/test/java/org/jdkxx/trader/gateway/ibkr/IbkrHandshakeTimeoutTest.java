package org.jdkxx.trader.gateway.ibkr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

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
import org.jdkxx.trader.gateway.GatewayState;
import org.junit.jupiter.api.Test;

/**
 * Unanswered handshake: the port is listening and the connection can be established, but the peer
 * never sends a single byte (the autossh tunnel's local port is still up while the link to the
 * server is down).
 *
 * <p>Before 2.0.2: the SDK's eConnect is synchronized and its read of the server version has no
 * timeout, so it held the client's object lock indefinitely; after the connect timeout the state
 * machine, inside its own lock, called close() and then isConnected() and waited for that lock,
 * hanging the scheduler thread forever, so status queries, manual disconnects and application
 * shutdown all froze.
 */
class IbkrHandshakeTimeoutTest {

  @Test
  void 握手无应答时状态查询与主动断开不被卡住() {
    assertTimeoutPreemptively(
        Duration.ofSeconds(20),
        () -> {
          try (ServerSocket blackhole = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            List<Socket> accepted = new CopyOnWriteArrayList<>();
            Thread acceptor =
                new Thread(
                    () -> {
                      while (!blackhole.isClosed()) {
                        try {
                          accepted.add(blackhole.accept()); // accept but never read or write
                        } catch (IOException e) {
                          return;
                        }
                      }
                    },
                    "blackhole-acceptor");
            acceptor.setDaemon(true);
            acceptor.start();

            IbkrProperties d = IbkrProperties.disabled();
            IbkrProperties props =
                new IbkrProperties(
                    true,
                    true,
                    "127.0.0.1",
                    blackhole.getLocalPort(),
                    12,
                    null,
                    "NASDAQ",
                    Duration.ofMillis(500),
                    d.requestTimeout(),
                    d.heartbeatInterval(),
                    d.heartbeatTimeout(),
                    40,
                    d.reconnect());
            IbkrGateway gateway = new IbkrGateway(props);
            ExecutorService caller =
                Executors.newSingleThreadExecutor(
                    r -> {
                      Thread t = new Thread(r, "test-caller");
                      t.setDaemon(true);
                      return t;
                    });
            try {
              gateway.connect();
              Thread.sleep(1_500); // the connect timeout (500ms) has passed
              assertThat(accepted).as("连接确实建立到了黑洞端口").isNotEmpty();

              Future<GatewayState> state = caller.submit(() -> gateway.status().state());
              assertThat(state.get(2, TimeUnit.SECONDS))
                  .as("状态查询立即返回")
                  .isIn(GatewayState.RECONNECTING, GatewayState.CONNECTING);

              caller.submit(gateway::disconnect).get(2, TimeUnit.SECONDS);
              assertThat(caller.submit(() -> gateway.status().state()).get(2, TimeUnit.SECONDS))
                  .isEqualTo(GatewayState.DISCONNECTED);
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
