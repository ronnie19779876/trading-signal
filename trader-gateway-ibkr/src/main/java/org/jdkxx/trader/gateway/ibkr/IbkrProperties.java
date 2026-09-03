package org.jdkxx.trader.gateway.ibkr;

import org.jdkxx.trader.gateway.support.ReconnectPolicy;
import org.jdkxx.trader.gateway.support.SupervisorSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 盈透网关配置（{@code trader.ibkr.*}）。
 *
 * <p>host / port / client-id / account 属于敏感配置，<b>不写在入库的 yml 里</b>：本机放
 * {@code config/secrets.yml}，服务器用环境变量 {@code TRADER_IBKR_HOST / TRADER_IBKR_PORT /
 * TRADER_IBKR_CLIENT_ID / TRADER_IBKR_ACCOUNT}（Spring 宽松绑定自动映射）。
 *
 * <p>client-id 每个实例必须独占：撞车时网关报 326 并把先连上的那个踢下线。
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

    /** 测试与骨架用：一份未启用的配置。 */
    public static IbkrProperties disabled() {
        return new IbkrProperties(false, true, null, null, null, null, "NASDAQ", Duration.ofSeconds(10),
                Duration.ofSeconds(15), Duration.ofSeconds(30), Duration.ofSeconds(10), 40,
                new Reconnect(Duration.ofSeconds(5), Duration.ofSeconds(60), -1));
    }

    /** 启用时校验必填项；未启用时什么都不查，允许骨架在没有任何网关配置的情况下启动。 */
    public void validate() {
        if (!enabled) {
            return;
        }
        require(host != null && !host.isBlank(), "trader.ibkr.host");
        require(port != null && port > 0, "trader.ibkr.port");
        require(clientId != null && clientId >= 0, "trader.ibkr.client-id");
    }

    public SupervisorSettings supervisorSettings() {
        return new SupervisorSettings(connectTimeout, heartbeatInterval, heartbeatTimeout, 2, reconnect.policy());
    }

    private static void require(boolean ok, String key) {
        if (!ok) {
            throw new IllegalStateException("trader.ibkr.enabled=true 但 " + key
                    + " 未配置或非法。该项属于敏感配置，请放在 config/secrets.yml（本机）或 deploy/config/trader.env（服务器）。");
        }
    }
}
