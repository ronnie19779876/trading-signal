package org.jdkxx.trader.gateway.support;

import java.time.Duration;

/**
 * @param heartbeatFailuresBeforeReconnect 连续多少次心跳失败后主动断开重连
 */
public record SupervisorSettings(Duration connectTimeout, Duration heartbeatInterval, Duration heartbeatTimeout,
                                 int heartbeatFailuresBeforeReconnect, ReconnectPolicy reconnect) {

    public static SupervisorSettings defaults() {
        return new SupervisorSettings(Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(10), 2,
                ReconnectPolicy.defaults());
    }
}
