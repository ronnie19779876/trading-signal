package org.jdkxx.trader.storage.marketdata;

import java.time.Instant;
import java.time.LocalDate;

public record BarSyncState(long instrumentId, String depth, LocalDate earliest, LocalDate latest, int barCount,
                           Instant lastSuccessAt, String lastError, Instant histQuotaUsedAt) {

    public static final String DEPTH_NONE = "NONE";
    public static final String DEPTH_KL1000 = "KL1000";
    public static final String DEPTH_HIST = "HIST20Y";
}
