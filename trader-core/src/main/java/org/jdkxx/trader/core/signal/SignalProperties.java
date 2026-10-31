package org.jdkxx.trader.core.signal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Entry signals ({@code trader.signal.*}). Rule parameters are not here - they are hard-coded with
 * the rule version ({@code SentinelThresholds}). Both crons must also be written into the in-jar
 * application.yml (placeholder resolution ignores the defaults here, see
 * ScheduledPlaceholdersTest).
 *
 * @param enabled when off, the scheduled evaluation is not wired (manual endpoints still work)
 * @param evaluationCron daily evaluation: after the 17:30 increment, the 17:40 valuation and the
 *     18:00 account snapshot (including the holdings sync)
 * @param catchupCron signal catch-up check: offset from the 21:00 market data catch-up, waiting for
 *     it to finish
 */
@ConfigurationProperties(prefix = "trader.signal")
public record SignalProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("0 10 18 * * MON-FRI") String evaluationCron,
    @DefaultValue("0 0 22 * * MON-FRI") String catchupCron) {}
