package org.jdkxx.trader.app.health;

import org.jdkxx.trader.core.gateway.GatewayRegistry;
import org.jdkxx.trader.gateway.BrokerGateway;
import org.jdkxx.trader.gateway.GatewayStatus;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

/**
 * Health indicator {@code gateways}: UP when every enabled gateway is healthy (or none is enabled);
 * otherwise DEGRADED (HTTP 200). A temporarily unreachable gateway must not fail the deploy
 * script's readiness check, but the daily check has to see the degradation at a glance. The 200
 * mapping and the status order are in application.yml ({@code management.endpoint.health.status}).
 */
@Component("gateways")
public class GatewaysHealthIndicator implements HealthIndicator {

  public static final Status DEGRADED = new Status("DEGRADED");

  private final GatewayRegistry registry;

  public GatewaysHealthIndicator(GatewayRegistry registry) {
    this.registry = registry;
  }

  @Override
  public Health health() {
    boolean degraded = false;
    Health.Builder b = Health.up();
    for (BrokerGateway g : registry.all()) {
      GatewayStatus s = g.status();
      b.withDetail(
          g.broker().name(), s.state().name() + (s.detail().isEmpty() ? "" : "：" + s.detail()));
      if (g.enabled() && !s.healthy()) {
        degraded = true;
      }
    }
    return degraded ? b.status(DEGRADED).build() : b.build();
  }
}
