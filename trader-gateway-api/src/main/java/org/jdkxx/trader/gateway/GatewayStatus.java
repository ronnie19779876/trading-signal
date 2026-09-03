package org.jdkxx.trader.gateway;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 网关状态快照。detail 与 facts 里不得出现主机、端口、账户号等敏感值。
 *
 * @param facts 可公开的事实（serverVersion、accounts=1、farms、opendVersion、qotLogined …），按键排序
 */
public record GatewayStatus(GatewayState state, String detail, Instant checkedAt, Instant connectedSince,
                            Instant lastHeartbeatAt, int reconnectAttempts, Map<String, String> facts) {

    public GatewayStatus {
        Objects.requireNonNull(state, "state");
        detail = detail == null ? "" : detail;
        checkedAt = checkedAt == null ? Instant.now() : checkedAt;
        facts = facts == null ? Collections.emptyMap() : Collections.unmodifiableMap(new TreeMap<>(facts));
    }

    public GatewayStatus(GatewayState state, String detail, Instant checkedAt) {
        this(state, detail, checkedAt, null, null, 0, null);
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
