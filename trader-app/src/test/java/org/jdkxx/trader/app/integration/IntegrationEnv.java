package org.jdkxx.trader.app.integration;

import org.jdkxx.trader.gateway.futu.FutuProperties;
import org.jdkxx.trader.gateway.ibkr.IbkrProperties;
import org.junit.jupiter.api.Assumptions;

import java.time.Duration;
import java.util.Map;

/**
 * 集成测试的连接参数只从环境变量读（不入库）：
 * TRADER_IBKR_HOST / TRADER_IBKR_PORT / TRADER_IBKR_TEST_CLIENT_ID（默认 91）、TRADER_FUTU_HOST / TRADER_FUTU_PORT。
 * 缺失则跳过对应测试。运行：./mvnw -pl trader-app -am verify -Dtrader.integration=true
 */
final class IntegrationEnv {

    private IntegrationEnv() {
    }

    static String env(String name) {
        String v = System.getenv(name);
        Assumptions.assumeTrue(v != null && !v.isBlank(), "缺少环境变量 " + name + "，跳过");
        return v.trim();
    }

    static int envInt(String name, int defaultValue) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? defaultValue : Integer.parseInt(v.trim());
    }

    static IbkrProperties ibkr(String host, int port, int clientId, Duration reconnectDelay) {
        return new IbkrProperties(true, false, host, port, clientId, null, "NASDAQ", Duration.ofSeconds(15),
                Duration.ofSeconds(15), Duration.ofSeconds(5), Duration.ofSeconds(5), 40,
                new IbkrProperties.Reconnect(reconnectDelay, Duration.ofSeconds(10), -1));
    }

    static FutuProperties futu(String host, int port, Duration reconnectDelay) {
        return new FutuProperties(true, false, host, port, "trading-signal-it", false, null, Duration.ofSeconds(15),
                Duration.ofSeconds(10), Duration.ofSeconds(5),
                new FutuProperties.Reconnect(reconnectDelay, Duration.ofSeconds(10), -1), Map.of());
    }
}
