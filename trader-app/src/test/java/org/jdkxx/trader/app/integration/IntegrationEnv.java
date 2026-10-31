package org.jdkxx.trader.app.integration;

import static org.assertj.core.api.Assertions.fail;

import java.time.Duration;
import java.util.Map;
import org.jdkxx.trader.gateway.futu.FutuProperties;
import org.jdkxx.trader.gateway.ibkr.IbkrProperties;

/**
 * Connection parameters of the integration tests are read only from environment variables (never
 * committed): TRADER_IBKR_HOST / TRADER_IBKR_PORT / TRADER_IBKR_TEST_CLIENT_ID, TRADER_FUTU_HOST /
 * TRADER_FUTU_PORT. Run: {@code ./mvnw -pl trader-app -am verify -Dtrader.integration=true}
 *
 * <p><b>A missing parameter fails, it does not skip</b> (changed in 3.1.2). Originally {@code
 * Assumptions.assumeTrue} skipped silently; together with the class-level
 * {@code @EnabledIfSystemProperty} and the command's {@code
 * -Dsurefire.failIfNoSpecifiedTests=false} that made three layers of silent passing: the whole
 * integration-test command could report BUILD SUCCESS <b>without connecting to a gateway even
 * once</b> (found in the 2026-09-25 full-project review). Someone who explicitly adds {@code
 * -Dtrader.integration=true} means only one thing: they want to really connect; a missing parameter
 * then is a configuration error and must be reported. To not run integration tests, leave out that
 * system property: the class-level annotation skips the whole class, which is the correct way to
 * skip.
 */
final class IntegrationEnv {

  private IntegrationEnv() {}

  static String env(String name) {
    String v = System.getenv(name);
    if (v == null || v.isBlank()) {
      fail(
          "集成测试缺少环境变量 %s。要么设上它，要么别加 -Dtrader.integration=true（整类跳过）。"
              + "别改回 assumeTrue：那样一次网关都没连也是 BUILD SUCCESS",
          name);
    }
    return v.trim();
  }

  /**
   * The client-id must be given explicitly too: each instance owns its own, and a hard-coded
   * default would leave an assigned value in the repository.
   */
  static int envInt(String name) {
    return Integer.parseInt(env(name));
  }

  static IbkrProperties ibkr(String host, int port, int clientId, Duration reconnectDelay) {
    return new IbkrProperties(
        true,
        false,
        host,
        port,
        clientId,
        null,
        "NASDAQ",
        Duration.ofSeconds(15),
        Duration.ofSeconds(15),
        Duration.ofSeconds(5),
        Duration.ofSeconds(5),
        40,
        new IbkrProperties.Reconnect(reconnectDelay, Duration.ofSeconds(10), -1));
  }

  static FutuProperties futu(String host, int port, Duration reconnectDelay) {
    return new FutuProperties(
        true,
        false,
        host,
        port,
        "trading-signal-it",
        false,
        null,
        Duration.ofSeconds(15),
        Duration.ofSeconds(10),
        Duration.ofSeconds(5),
        new FutuProperties.Reconnect(reconnectDelay, Duration.ofSeconds(10), -1),
        Map.of());
  }
}
