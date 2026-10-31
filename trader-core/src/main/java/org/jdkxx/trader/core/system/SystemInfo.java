package org.jdkxx.trader.core.system;

import java.time.Instant;
import java.util.List;
import org.jdkxx.trader.ai.AiStatus;
import org.jdkxx.trader.core.gateway.GatewayViews;
import org.jdkxx.trader.storage.status.DatabaseStatus;

/**
 * Response model of the system page (GET /api/system/info). Every field is safe to show: no hosts,
 * ports, account numbers or keys.
 */
public record SystemInfo(
    String application,
    String version,
    Instant buildTime,
    String environment,
    Instant serverTime,
    DatabaseStatus database,
    List<GatewayViews.GatewayView> gateways,
    AiStatus ai) {}
