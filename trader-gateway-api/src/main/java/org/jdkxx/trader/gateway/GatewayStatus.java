package org.jdkxx.trader.gateway;

import java.time.Instant;
import java.util.Objects;

/**
 * 网关状态快照：状态 + 人类可读说明 + 判定时刻。说明里不得出现主机、端口、账户号等敏感值。
 */
public record GatewayStatus(GatewayState state, String detail, Instant checkedAt) {

    public GatewayStatus {
        Objects.requireNonNull(state, "state");
        detail = detail == null ? "" : detail;
        checkedAt = checkedAt == null ? Instant.now() : checkedAt;
    }

    public static GatewayStatus disabled(String detail) {
        return new GatewayStatus(GatewayState.DISABLED, detail, Instant.now());
    }

    public static GatewayStatus disconnected(String detail) {
        return new GatewayStatus(GatewayState.DISCONNECTED, detail, Instant.now());
    }

    public static GatewayStatus connected(String detail) {
        return new GatewayStatus(GatewayState.CONNECTED, detail, Instant.now());
    }

    public static GatewayStatus error(String detail) {
        return new GatewayStatus(GatewayState.ERROR, detail, Instant.now());
    }

    /** 健康 = 已连接，或根本没启用（未启用不是故障）。 */
    public boolean healthy() {
        return state == GatewayState.CONNECTED || state == GatewayState.DISABLED;
    }
}
