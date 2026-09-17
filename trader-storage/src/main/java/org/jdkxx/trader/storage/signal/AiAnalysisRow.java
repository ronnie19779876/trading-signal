package org.jdkxx.trader.storage.signal;

import com.fasterxml.jackson.annotation.JsonRawValue;

import java.time.Instant;
import java.time.LocalDate;

/** 一次模型分析。input / judgment / checks 是 JSON 原文，接口里原样嵌入；symbol 查询时联表带出。 */
public record AiAnalysisRow(
        long id,
        long instrumentId,
        String symbol,
        LocalDate tradeDate,
        String purpose,
        Long signalId,
        String promptVersion,
        String model,
        String reasoningEffort,
        String inputHash,
        @JsonRawValue String input,
        String status,
        @JsonRawValue String judgment,
        String outputText,
        String stance,
        String confidence,
        String verdict,
        String verdictReason,
        @JsonRawValue String checks,
        Integer verifiedBear,
        Integer unverified,
        String error,
        String responseId,
        Integer inputTokens,
        Integer cachedTokens,
        Integer outputTokens,
        Integer reasoningTokens,
        Integer latencyMs,
        Long jobRunId,
        Instant createdAt) {
}
