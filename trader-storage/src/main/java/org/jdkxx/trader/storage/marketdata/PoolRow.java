package org.jdkxx.trader.storage.marketdata;

import java.time.Instant;
import org.jdkxx.trader.domain.PoolRole;

public record PoolRow(long instrumentId, PoolRole role, String note, Instant addedAt) {}
