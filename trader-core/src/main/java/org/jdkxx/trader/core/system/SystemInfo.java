package org.jdkxx.trader.core.system;

import org.jdkxx.trader.ai.AiStatus;
import org.jdkxx.trader.storage.status.DatabaseStatus;

import java.time.Instant;
import java.util.List;

/**
 * 系统页（GET /api/system/info）的响应模型。所有字段都是可以公开展示的：没有主机、端口、账户号、密钥。
 */
public record SystemInfo(
        String application,
        String version,
        Instant buildTime,
        String environment,
        Instant serverTime,
        DatabaseStatus database,
        List<GatewayView> gateways,
        AiStatus ai) {

    public record GatewayView(String broker, String displayName, String role, String state, boolean healthy,
                              String detail, Instant checkedAt) {
    }
}
