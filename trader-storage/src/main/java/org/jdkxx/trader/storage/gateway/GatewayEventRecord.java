package org.jdkxx.trader.storage.gateway;

import java.time.Instant;
import org.jdkxx.trader.domain.Broker;

public record GatewayEventRecord(
    long id, Broker broker, String event, String detail, Instant occurredAt) {}
