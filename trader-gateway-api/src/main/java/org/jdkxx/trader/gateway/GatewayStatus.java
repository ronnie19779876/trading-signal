package org.jdkxx.trader.gateway;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Gateway status snapshot. {@code detail} and {@code facts} must never contain sensitive values
 * such as hosts, ports or account numbers.
 *
 * @param facts publishable facts (serverVersion, accounts=1, farms, opendVersion, qotLogined ...),
 *     sorted by key
 */
public record GatewayStatus(
    GatewayState state,
    String detail,
    Instant checkedAt,
    Instant connectedSince,
    Instant lastHeartbeatAt,
    int reconnectAttempts,
    Map<String, String> facts) {

  public GatewayStatus {
    Objects.requireNonNull(state, "state");
    detail = detail == null ? "" : detail;
    checkedAt = checkedAt == null ? Instant.now() : checkedAt;
    facts =
        facts == null ? Collections.emptyMap() : Collections.unmodifiableMap(new TreeMap<>(facts));
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

  /** Healthy = connected, or not enabled at all (being disabled is not a fault). */
  public boolean healthy() {
    return state == GatewayState.CONNECTED || state == GatewayState.DISABLED;
  }
}
