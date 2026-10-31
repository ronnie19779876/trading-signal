package org.jdkxx.trader.gateway.futu;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jdkxx.trader.common.ratelimit.RateLimitSpec;
import org.jdkxx.trader.gateway.support.ReconnectPolicy;
import org.jdkxx.trader.gateway.support.SupervisorSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Futu gateway configuration ({@code trader.futu.*}).
 *
 * <p>host / port (and the private-key file path when encrypt=true) are sensitive configuration and
 * are not written to the committed yml: locally they live in {@code config/secrets.yml}, on the
 * server in the environment variables {@code TRADER_FUTU_HOST / TRADER_FUTU_PORT}. The MD5 of the
 * trading password will later go into {@code trader.futu.trade-unlock-md5} (not needed in this
 * phase).
 *
 * <p>limits: rate limits per request name (written as calls/window); requests that are not
 * configured use the conservative defaults in code.
 */
@ConfigurationProperties(prefix = "trader.futu")
public record FutuProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("true") boolean autoConnect,
    String host,
    Integer port,
    @DefaultValue("trading-signal") String clientInfo,
    @DefaultValue("false") boolean encrypt,
    String rsaPrivateKeyFile,
    @DefaultValue("10s") Duration connectTimeout,
    @DefaultValue("10s") Duration replyTimeout,
    @DefaultValue("30s") Duration healthInterval,
    @DefaultValue Reconnect reconnect,
    Map<String, String> limits) {

  public record Reconnect(
      @DefaultValue("5s") Duration initialDelay,
      @DefaultValue("60s") Duration maxDelay,
      @DefaultValue("-1") int maxAttempts) {

    public ReconnectPolicy policy() {
      return new ReconnectPolicy(initialDelay, maxDelay, maxAttempts, 0.2);
    }
  }

  /**
   * Conservative default rate limits (calls/window), taken from the "API limits" section of the
   * official docs; can be overridden in configuration.
   */
  public static final Map<String, String> DEFAULT_LIMITS =
      Map.ofEntries(
          Map.entry("get-global-state", "60/30s"),
          Map.entry("get-acc-list", "10/30s"),
          Map.entry("sub", "30/30s"),
          Map.entry("get-kl", "60/30s"),
          Map.entry("request-history-kl", "60/30s"),
          Map.entry("request-history-kl-quota", "10/30s"),
          Map.entry(
              "request-rehab",
              // docs say 60/30s, but sending at 60 was rejected in testing; keep headroom
              "50/30s"),
          Map.entry("request-trade-date", "30/30s"),
          Map.entry("get-static-info", "30/30s"),
          Map.entry("get-sub-info", "10/30s"),
          Map.entry("get-security-snapshot", "60/30s"),
          Map.entry(
              "get-financials",
              // docs say 30/30s, but sending at 30 was rejected in testing; keep headroom (same as
              // request-rehab)
              "25/30s"),
          Map.entry(
              "get-company-profile",
              // limit not documented; configured like similar requests for now, to be tightened
              // after measurement
              "30/30s"),
          // Quant stock selection (§25.6): limits not documented; conservatively 10/30s for now, to
          // be relaxed after measurement
          Map.entry("get-valuation-detail", "10/30s"),
          Map.entry("get-earnings-history", "10/30s"));

  public FutuProperties {
    Map<String, String> merged = new LinkedHashMap<>(DEFAULT_LIMITS);
    if (limits != null) {
      merged.putAll(limits);
    }
    limits = Map.copyOf(merged);
  }

  public static FutuProperties disabled() {
    return new FutuProperties(
        false,
        true,
        null,
        null,
        "trading-signal",
        false,
        null,
        Duration.ofSeconds(10),
        Duration.ofSeconds(10),
        Duration.ofSeconds(30),
        new Reconnect(Duration.ofSeconds(5), Duration.ofSeconds(60), -1),
        null);
  }

  public void validate() {
    if (!enabled) {
      return;
    }
    require(host != null && !host.isBlank(), "trader.futu.host");
    require(port != null && port > 0, "trader.futu.port");
    if (encrypt) {
      require(
          rsaPrivateKeyFile != null && !rsaPrivateKeyFile.isBlank(),
          "trader.futu.rsa-private-key-file");
    }
    limits.forEach((k, v) -> RateLimitSpec.parse(v));
  }

  public RateLimitSpec limit(String name) {
    return RateLimitSpec.parse(limits.get(name));
  }

  public SupervisorSettings supervisorSettings() {
    return new SupervisorSettings(
        connectTimeout, healthInterval, replyTimeout, 2, reconnect.policy());
  }

  private static void require(boolean ok, String key) {
    if (!ok) {
      throw new IllegalStateException(
          "trader.futu.enabled=true 但 "
              + key
              + " 未配置或非法。该项属于敏感配置，请放在 config/secrets.yml（本机）或 deploy/config/trader.env（服务器）。");
    }
  }
}
