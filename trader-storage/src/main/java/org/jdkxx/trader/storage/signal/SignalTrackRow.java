package org.jdkxx.trader.storage.signal;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** One paper ledger row (one signal × one exit variant); prices are in decision-day terms. */
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
    /**
     * Current price (decision-day terms, directly comparable with entryPrice); only filled for OPEN
     * rows.
     */
    BigDecimal lastClose,
    /**
     * Date of the bar behind the current price; <b>may be earlier than updatedThrough</b>
     * (suspension or missing bar), so it must always be shown alongside.
     */
    LocalDate lastCloseDate,
    /**
     * Unrealized P&amp;L ÷ R. R = decision-day close − this variant's stop, so the two variants
     * usually differ (they only match when the stop comes from the zone-bottom leg).
     */
    BigDecimal unrealizedR,
    Integer barsHeld,
    LocalDate updatedThrough,
    Instant updatedAt) {}
