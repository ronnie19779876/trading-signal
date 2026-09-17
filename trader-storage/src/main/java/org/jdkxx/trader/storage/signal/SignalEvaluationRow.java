package org.jdkxx.trader.storage.signal;

import com.fasterxml.jackson.annotation.JsonRawValue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 每日评估一行。symbol 查询时联表带出（写入时忽略）；detail 是判定明细的 JSON 原文，只有一部分行存了，接口里原样嵌入。
 */
public record SignalEvaluationRow(
        long instrumentId,
        String symbol,
        LocalDate tradeDate,
        String rulesetVersion,
        String status,
        String statusDetail,
        String outcome,
        String gates,
        int gatesPassed,
        String firstBlockingGate,
        String role,
        BigDecimal close,
        BigDecimal atr14,
        BigDecimal rvol,
        BigDecimal zoneBottom,
        BigDecimal stop,
        BigDecimal stopDistance,
        String inputFingerprint,
        @JsonRawValue String detail,
        Long jobRunId,
        Instant evaluatedAt) {
}
