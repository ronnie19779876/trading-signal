package org.jdkxx.trader.gateway.support;

import java.time.Duration;

/**
 * Timing settings of a {@link ConnectionSupervisor}.
 *
 * @param connectTimeout how long one connect attempt may take to become ready
 * @param heartbeatInterval delay between heartbeat probes while connected
 * @param heartbeatTimeout how long one probe may stay unanswered before it counts as failed
 * @param heartbeatFailuresBeforeReconnect number of consecutive heartbeat failures after which the
 *     supervisor drops the connection and reconnects
 */
public record SupervisorSettings(
    Duration connectTimeout,
    Duration heartbeatInterval,
    Duration heartbeatTimeout,
    int heartbeatFailuresBeforeReconnect,
    ReconnectPolicy reconnect) {

  public static SupervisorSettings defaults() {
    return new SupervisorSettings(
        Duration.ofSeconds(10),
        Duration.ofSeconds(30),
        Duration.ofSeconds(10),
        2,
        ReconnectPolicy.defaults());
  }
}
