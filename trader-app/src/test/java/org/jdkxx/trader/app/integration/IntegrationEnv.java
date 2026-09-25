package org.jdkxx.trader.app.integration;

import org.jdkxx.trader.gateway.futu.FutuProperties;
import org.jdkxx.trader.gateway.ibkr.IbkrProperties;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.fail;

/**
 * 集成测试的连接参数只从环境变量读（不入库）：
 * TRADER_IBKR_HOST / TRADER_IBKR_PORT / TRADER_IBKR_TEST_CLIENT_ID、TRADER_FUTU_HOST / TRADER_FUTU_PORT。
 * 运行：{@code ./mvnw -pl trader-app -am verify -Dtrader.integration=true}
 *
 * <p><b>缺参数判失败，不跳过</b>（3.1.2 改）。原先用 {@code Assumptions.assumeTrue} 静默跳过，
 * 叠上类级的 {@code @EnabledIfSystemProperty} 与命令里的 {@code -Dsurefire.failIfNoSpecifiedTests=false}，
 * 形成三重静默通过：整条集成测试命令可以在<b>一次网关都没连</b>的情况下 BUILD SUCCESS
 * （2026-09-25 全项目审查发现）。而人会显式加 {@code -Dtrader.integration=true} 只有一个意思——
 * 他要真的连一次；这时候缺参数是配置错了，必须说出来。
 * 不想跑集成测试就别加那个系统属性，类级注解会整类跳过，那才是正确的跳过方式。
 */
final class IntegrationEnv {

    private IntegrationEnv() {
    }

    static String env(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            fail("集成测试缺少环境变量 %s。要么设上它，要么别加 -Dtrader.integration=true（整类跳过）。"
                    + "别改回 assumeTrue：那样一次网关都没连也是 BUILD SUCCESS", name);
        }
        return v.trim();
    }

    /** client-id 也必须显式给：每个实例独占，写死默认值等于把分配值留在仓库里。 */
    static int envInt(String name) {
        return Integer.parseInt(env(name));
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
