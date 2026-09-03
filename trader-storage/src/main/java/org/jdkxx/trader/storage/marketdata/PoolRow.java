package org.jdkxx.trader.storage.marketdata;

import org.jdkxx.trader.domain.PoolRole;

import java.time.Instant;

public record PoolRow(long instrumentId, PoolRole role, String note, Instant addedAt) {
}
