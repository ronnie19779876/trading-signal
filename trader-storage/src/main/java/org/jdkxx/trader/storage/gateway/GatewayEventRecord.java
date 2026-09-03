package org.jdkxx.trader.storage.gateway;

import org.jdkxx.trader.domain.Broker;

import java.time.Instant;

public record GatewayEventRecord(long id, Broker broker, String event, String detail, Instant occurredAt) {
}
