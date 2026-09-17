package org.jdkxx.trader.storage.signal;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** 纸面账本一行（一条信号 × 一个出场变体）；价格为判定日口径。 */
public record SignalTrackRow(
        long signalId,
        String variant,
        String status,
        BigDecimal stop,
        BigDecimal plusOneR,
        LocalDate entryDate,
        BigDecimal entryPrice,
        boolean touchedPlusOneR,
        LocalDate exitDate,
        BigDecimal exitPrice,
        String exitReason,
        BigDecimal rMultiple,
        BigDecimal returnPct,
        BigDecimal mfeR,
        BigDecimal maeR,
        Integer barsHeld,
        LocalDate updatedThrough,
        Instant updatedAt) {
}
