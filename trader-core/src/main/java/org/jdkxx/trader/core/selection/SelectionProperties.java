package org.jdkxx.trader.core.selection;

import java.time.LocalDate;
import org.jdkxx.trader.domain.selection.PriceSeam;
import org.jdkxx.trader.domain.selection.ValuationRange;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Quant selection ({@code trader.selection.*}, ARCHITECTURE §25). Scoring parameters are hard-coded
 * with the model version; this only holds the switch, the schedule and the fetch ranges for input
 * backfill. All four crons must also be written into the in-jar application.yml (placeholder
 * resolution ignores the defaults here, see ScheduledPlaceholdersTest).
 *
 * @param enabled when off, the scheduled jobs are not wired (manual endpoints still work)
 * @param dailyCron daily scoring: after the 17:40 valuation snapshot and the 18:10 signal
 *     evaluation
 * @param catchupCron rerun when today has no scores
 * @param weeklyCron weekly refresh of announcement dates and security types
 * @param simWeeklyCron simulated-trading weekly report (Saturday, after the weekly refresh)
 * @param priceFrom start date for the IBKR daily bar backfill (12-month momentum for a backtest
 *     from 2018 needs 2017 onwards; one extra year of margin)
 * @param seamMonths how many months past the start of Futu's daily bars to fetch IBKR bars, so
 *     there is an overlap to check
 * @param financialPeriods number of quarterly reports to fetch (Futu's per-request cap is 50,
 *     measured to reach back to about 2014)
 * @param valuationRange look-back range of the valuation history
 * @param seamMinCompared minimum number of pairs the seam check must compare
 * @param seamTolerance tolerance for the daily return difference at the seam (fraction)
 * @param seamMinShare minimum share of compared days within tolerance
 */
@ConfigurationProperties(prefix = "trader.selection")
public record SelectionProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("0 30 18 * * MON-FRI") String dailyCron,
    @DefaultValue("0 30 22 * * MON-FRI") String catchupCron,
    @DefaultValue("0 0 9 * * SAT") String weeklyCron,
    @DefaultValue("0 0 10 * * SAT") String simWeeklyCron,
    @DefaultValue("2016-01-01") LocalDate priceFrom,
    @DefaultValue("4") int seamMonths,
    @DefaultValue("50") int financialPeriods,
    @DefaultValue("YEAR10") ValuationRange valuationRange,
    @DefaultValue("60") int seamMinCompared,
    @DefaultValue("0.001") double seamTolerance,
    @DefaultValue("0.95") double seamMinShare) {

  public PriceSeam.Thresholds seamThresholds() {
    return new PriceSeam.Thresholds(seamMinCompared, seamTolerance, seamMinShare);
  }
}
