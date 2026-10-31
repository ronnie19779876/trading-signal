package org.jdkxx.trader.domain.valuation;

/**
 * Company-level baseline for the reverse calculation and the consistency checks; filled in
 * automatically by the system, never by hand.
 *
 * @param currentPrice current price (latest close)
 * @param baselinePe baseline P/E (median of the valid daily-bar P/E values; <b>zeros are treated as
 *     invalid and already removed</b>); null when unavailable, in which case the reverse
 *     calculation gives no conclusion
 * @param currentNetIncome current net income attributable to shareholders (TTM or the latest period
 *     annualized), used to back out the required growth; null when unavailable or not positive
 * @param actualNetMargin actual consolidated net margin of the latest period, as a <b>fraction</b>;
 *     used to check whether the per-segment net margins contradict each other; null when
 *     unavailable
 */
public record SotpBasis(
    double currentPrice, Double baselinePe, Double currentNetIncome, Double actualNetMargin) {}
