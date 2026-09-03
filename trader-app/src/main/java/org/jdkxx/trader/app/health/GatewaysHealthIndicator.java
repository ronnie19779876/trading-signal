package org.jdkxx.trader.app.health;

import org.jdkxx.trader.core.gateway.GatewayRegistry;
import org.jdkxx.trader.gateway.BrokerGateway;
import org.jdkxx.trader.gateway.GatewayStatus;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

/**
 * 健康指标 {@code gateways}：所有已启用的网关都 CONNECTED（或没有启用的）→ UP；
 * 否则 DEGRADED（HTTP 200）——网关暂时不可达不应让部署脚本的就绪判断失败，但巡检要一眼看出降级。
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
            b.withDetail(g.broker().name(), s.state().name() + (s.detail().isEmpty() ? "" : "：" + s.detail()));
            if (g.enabled() && !s.healthy()) {
                degraded = true;
            }
        }
        return degraded ? b.status(DEGRADED).build() : b.build();
    }
}
