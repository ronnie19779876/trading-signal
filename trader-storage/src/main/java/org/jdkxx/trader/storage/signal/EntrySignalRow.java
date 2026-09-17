package org.jdkxx.trader.storage.signal;

import com.fasterxml.jackson.annotation.JsonRawValue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** 入场信号一行；价位为判定日口径。symbol 查询时联表带出。 */
public record EntrySignalRow(
        long id,
        long instrumentId,
        String symbol,
        LocalDate tradeDate,
        String rulesetVersion,
        String role,
        String origin,
        BigDecimal close,
        BigDecimal atr14,
        BigDecimal stop,
        String stopLeg,
        BigDecimal stopDistance,
        BigDecimal riskPerShare,
        BigDecimal plusOneR,
        BigDecimal chandelierStop,
        BigDecimal target,
        BigDecimal rewardRisk,
        BigDecimal zoneBottom,
        BigDecimal zoneTop,
        Integer zoneTouches,
        @JsonRawValue String bonus,
        Long aiAnalysisId,
        String aiStance,
        String status,
        LocalDate expiresOn,
        String note,
        Instant statusChangedAt,
        Long jobRunId,
        Instant createdAt) {
}
