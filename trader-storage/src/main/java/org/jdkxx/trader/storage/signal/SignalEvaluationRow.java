package org.jdkxx.trader.storage.signal;

import com.fasterxml.jackson.annotation.JsonRawValue;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One daily evaluation. symbol is joined in on reads (ignored on writes); detail is the raw JSON of
 * the decision details, stored only for some rows, and embedded as-is in API responses. outcome is
 * null for skipped evaluations.
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
    Instant evaluatedAt) {}
