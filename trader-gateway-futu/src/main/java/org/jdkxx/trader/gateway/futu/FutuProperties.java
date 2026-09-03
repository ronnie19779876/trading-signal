package org.jdkxx.trader.gateway.futu;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 富途网关配置（{@code trader.futu.*}）。
 *
 * <p>host / port 属于敏感配置，不写在入库的 yml 里：本机放 {@code config/secrets.yml}，
 * 服务器用环境变量 {@code TRADER_FUTU_HOST / TRADER_FUTU_PORT}。交易密码 MD5 将来放
 * {@code trader.futu.trade-unlock-md5}（本期不需要）。
 *
 * <p>encrypt：OpenD 监听非本地地址时交易接口强制 RSA 加密；经 SSH 隧道或同机连接时为 false。
 */
@ConfigurationProperties(prefix = "trader.futu")
public record FutuProperties(
        @DefaultValue("false") boolean enabled,
        String host,
        Integer port,
        @DefaultValue("trading-signal") String clientInfo,
        @DefaultValue("false") boolean encrypt,
        @DefaultValue("10s") Duration replyTimeout) {

    public void validate() {
        if (!enabled) {
            return;
        }
        require(host != null && !host.isBlank(), "trader.futu.host");
        require(port != null && port > 0, "trader.futu.port");
    }

    private static void require(boolean ok, String key) {
        if (!ok) {
            throw new IllegalStateException("trader.futu.enabled=true 但 " + key
                    + " 未配置或非法。该项属于敏感配置，请放在 config/secrets.yml（本机）或 deploy/config/trader.env（服务器）。");
        }
    }
}
