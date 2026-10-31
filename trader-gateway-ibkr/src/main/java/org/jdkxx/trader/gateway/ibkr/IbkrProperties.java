package org.jdkxx.trader.gateway.ibkr;

import java.time.Duration;
import org.jdkxx.trader.gateway.support.ReconnectPolicy;
import org.jdkxx.trader.gateway.support.SupervisorSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * IBKR gateway configuration ({@code trader.ibkr.*}).
 *
 * <p>host / port / client-id / account are sensitive and are <b>not written in the committed
 * yml</b>: locally they go in {@code config/secrets.yml}; on servers they come from the environment
 * variables {@code TRADER_IBKR_HOST / TRADER_IBKR_PORT / TRADER_IBKR_CLIENT_ID /
 * TRADER_IBKR_ACCOUNT} (mapped automatically by Spring relaxed binding).
 *
 * <p>Each instance must own its client-id exclusively: on a clash the gateway reports 326 and kicks
 * off whichever client connected first.
 */
@ConfigurationProperties(prefix = "trader.ibkr")
public record IbkrProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("true") boolean autoConnect,
    String host,
    Integer port,
    Integer clientId,
    String account,
    @DefaultValue("NASDAQ") String primaryExchange,
    @DefaultValue("10s") Duration connectTimeout,
    @DefaultValue("15s") Duration requestTimeout,
    @DefaultValue("30s") Duration heartbeatInterval,
    @DefaultValue("10s") Duration heartbeatTimeout,
    @DefaultValue("40") int messageRatePerSecond,
    @DefaultValue Reconnect reconnect) {

  public record Reconnect(
      @DefaultValue("5s") Duration initialDelay,
      @DefaultValue("60s") Duration maxDelay,
      @DefaultValue("-1") int maxAttempts) {

    public ReconnectPolicy policy() {
      return new ReconnectPolicy(initialDelay, maxDelay, maxAttempts, 0.2);
    }
  }

  /** For tests and the skeleton: a configuration that is not enabled. */
  public static IbkrProperties disabled() {
    return new IbkrProperties(
        false,
        true,
        null,
        null,
        null,
        null,
        "NASDAQ",
        Duration.ofSeconds(10),
        Duration.ofSeconds(15),
        Duration.ofSeconds(30),
        Duration.ofSeconds(10),
        40,
        new Reconnect(Duration.ofSeconds(5), Duration.ofSeconds(60), -1));
  }

  /**
   * Validates the required settings when enabled; when disabled nothing is checked, so the skeleton
   * can start without any gateway configuration at all.
   */
  public void validate() {
    if (!enabled) {
      return;
    }
    require(host != null && !host.isBlank(), "trader.ibkr.host");
    require(port != null && port > 0, "trader.ibkr.port");
    require(clientId != null && clientId >= 0, "trader.ibkr.client-id");
  }

  public SupervisorSettings supervisorSettings() {
    return new SupervisorSettings(
        connectTimeout, heartbeatInterval, heartbeatTimeout, 2, reconnect.policy());
  }

  private static void require(boolean ok, String key) {
    if (!ok) {
      throw new IllegalStateException(
          "trader.ibkr.enabled=true 但 "
              + key
              + " 未配置或非法。该项属于敏感配置，请放在 config/secrets.yml（本机）或 deploy/config/trader.env（服务器）。");
    }
  }
}
