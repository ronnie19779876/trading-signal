package org.jdkxx.trader.storage.signal;

import com.fasterxml.jackson.annotation.JsonRawValue;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One model analysis. input / judgment / checks are raw JSON embedded as-is in API responses;
 * symbol is joined in on reads.
 */
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
    Instant createdAt) {}
