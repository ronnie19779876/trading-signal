package org.jdkxx.trader.app.integration;

import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.gateway.BrokerGateway;
import org.jdkxx.trader.gateway.GatewayListener;
import org.jdkxx.trader.gateway.GatewayState;
import org.jdkxx.trader.gateway.futu.FutuGateway;
import org.jdkxx.trader.gateway.ibkr.IbkrGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 断线重连实测：网关 → 本地 TCP 中继 → 真实网关。切断中继里的连接后应进入 RECONNECTING，
 * 中继仍在则自动恢复 CONNECTED 并发出 reconnected=true 事件。
 */
@EnabledIfSystemProperty(named = "trader.integration", matches = "true")
class ReconnectIT {

    private static final class Events implements GatewayListener {
        final List<String> list = new CopyOnWriteArrayList<>();

        @Override
        public void onConnected(Broker broker, boolean reconnected) {
            list.add(reconnected ? "reconnected" : "connected");
        }

        @Override
        public void onDisconnected(Broker broker, String reason) {
            list.add("disconnected:" + reason);
        }
    }

    private static void await(String what, Duration timeout, BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("等待「" + what + "」超时 " + timeout);
            }
            Thread.sleep(200);
        }
    }

    private static void exercise(BrokerGateway gateway, TcpRelay relay, Events events) throws Exception {
        gateway.addListener(events);
        gateway.connect().get(20, TimeUnit.SECONDS);
        await("首次连接事件", Duration.ofSeconds(5), () -> events.list.contains("connected"));
        assertThat(relay.activeConnections()).isGreaterThanOrEqualTo(1);

        relay.dropConnections();
        await("进入 RECONNECTING", Duration.ofSeconds(15), () -> gateway.status().state() == GatewayState.RECONNECTING
                || events.list.stream().anyMatch(e -> e.startsWith("disconnected")));
        System.out.println("[IT] " + gateway.broker() + " 断线后状态=" + gateway.status().state() + " detail=" + gateway.status().detail());

        await("自动恢复 CONNECTED", Duration.ofSeconds(40), () -> gateway.status().state() == GatewayState.CONNECTED);
        await("reconnected 事件", Duration.ofSeconds(5), () -> events.list.contains("reconnected"));
        System.out.println("[IT] " + gateway.broker() + " 事件=" + events.list);
        assertThat(gateway.status().reconnectAttempts()).isZero();
    }

    @Test
    void 盈透断线自动重连() throws Exception {
        String host = IntegrationEnv.env("TRADER_IBKR_HOST");
        int port = Integer.parseInt(IntegrationEnv.env("TRADER_IBKR_PORT"));
        int clientId = IntegrationEnv.envInt("TRADER_IBKR_TEST_CLIENT_ID", 91) + 1;
        try (TcpRelay relay = new TcpRelay(host, port);
             IbkrGateway gateway = new IbkrGateway(IntegrationEnv.ibkr("127.0.0.1", relay.port(), clientId, Duration.ofSeconds(1)))) {
            exercise(gateway, relay, new Events());
            gateway.disconnect();
        }
    }

    @Test
    void 富途断线自动重连() throws Exception {
        String host = IntegrationEnv.env("TRADER_FUTU_HOST");
        int port = Integer.parseInt(IntegrationEnv.env("TRADER_FUTU_PORT"));
        try (TcpRelay relay = new TcpRelay(host, port);
             FutuGateway gateway = new FutuGateway(IntegrationEnv.futu("127.0.0.1", relay.port(), Duration.ofSeconds(1)))) {
            exercise(gateway, relay, new Events());
            gateway.disconnect();
        }
    }
}
