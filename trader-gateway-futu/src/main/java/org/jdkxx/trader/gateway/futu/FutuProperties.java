package org.jdkxx.trader.gateway.futu;

import org.jdkxx.trader.common.ratelimit.RateLimitSpec;
import org.jdkxx.trader.gateway.support.ReconnectPolicy;
import org.jdkxx.trader.gateway.support.SupervisorSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 富途网关配置（{@code trader.futu.*}）。
 *
 * <p>host / port（以及 encrypt=true 时的私钥文件路径）属于敏感配置，不写在入库的 yml 里：本机放
 * {@code config/secrets.yml}，服务器用环境变量 {@code TRADER_FUTU_HOST / TRADER_FUTU_PORT}。
 * 交易密码 MD5 将来放 {@code trader.futu.trade-unlock-md5}（本期不需要）。
 *
 * <p>limits：按接口名的限频（写法 次数/窗口），未配置的接口用代码里的保守默认值。
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

    /** 保守的默认限频（次数/窗口），来自官方文档"接口限制"；可在配置里覆盖。 */
    public static final Map<String, String> DEFAULT_LIMITS = Map.ofEntries(
            Map.entry("get-global-state", "60/30s"),
            Map.entry("get-acc-list", "10/30s"),
            Map.entry("sub", "30/30s"),
            Map.entry("get-kl", "60/30s"),
            Map.entry("request-history-kl", "60/30s"),
            Map.entry("request-history-kl-quota", "10/30s"),
            Map.entry("request-rehab", "60/30s"),
            Map.entry("request-trade-date", "30/30s"),
            Map.entry("get-static-info", "30/30s"));

    public FutuProperties {
        Map<String, String> merged = new LinkedHashMap<>(DEFAULT_LIMITS);
        if (limits != null) {
            merged.putAll(limits);
        }
        limits = Map.copyOf(merged);
    }

    public static FutuProperties disabled() {
        return new FutuProperties(false, true, null, null, "trading-signal", false, null, Duration.ofSeconds(10),
                Duration.ofSeconds(10), Duration.ofSeconds(30),
                new Reconnect(Duration.ofSeconds(5), Duration.ofSeconds(60), -1), null);
    }

    public void validate() {
        if (!enabled) {
            return;
        }
        require(host != null && !host.isBlank(), "trader.futu.host");
        require(port != null && port > 0, "trader.futu.port");
        if (encrypt) {
            require(rsaPrivateKeyFile != null && !rsaPrivateKeyFile.isBlank(), "trader.futu.rsa-private-key-file");
        }
        limits.forEach((k, v) -> RateLimitSpec.parse(v));
    }

    public RateLimitSpec limit(String name) {
        return RateLimitSpec.parse(limits.get(name));
    }

    public SupervisorSettings supervisorSettings() {
        return new SupervisorSettings(connectTimeout, healthInterval, replyTimeout, 2, reconnect.policy());
    }

    private static void require(boolean ok, String key) {
        if (!ok) {
            throw new IllegalStateException("trader.futu.enabled=true 但 " + key
                    + " 未配置或非法。该项属于敏感配置，请放在 config/secrets.yml（本机）或 deploy/config/trader.env（服务器）。");
        }
    }
}
