package org.jdkxx.trader.core.marketdata.jobs;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import org.jdkxx.trader.core.marketdata.MarketDataProperties;
import org.jdkxx.trader.core.marketdata.bars.DailyIncrementService;
import org.jdkxx.trader.core.marketdata.universe.UniverseScope;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.storage.marketdata.DailyBarRepository;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;
import org.jdkxx.trader.storage.marketdata.ValuationRepository;

/**
 * Same-day catch-up check: some time after the close, confirm that today's data is complete;
 * whatever is missing is re-run by the scheduler.
 *
 * <p>Why it exists: the scheduler's collision retry only covers "the job thread is busy". It does
 * not cover "the application was restarting when the job should have run", "the job FAILED" or "the
 * server was down at the time". Valuation snapshots are <b>point-in-time data</b>: the broker's
 * snapshot API takes no date parameter, so a missed day's market cap and P/E are gone for good.
 *
 * <p>Why it can still catch up at this hour: the broker freezes curPrice after the close until the
 * next pre-market (04:00 ET). Measured: a snapshot taken at 20:49 US/Eastern still carried that
 * day's closing basis (market cap = shares × that day's close, error 0.0000%). So any re-run
 * between 16:00 ET and 04:00 ET the next morning is valid; the job is scheduled at 21:00 ET, 7
 * hours before the window closes.
 *
 * <p>This service <b>only decides and never submits jobs</b>: it runs on the scheduler thread, not
 * the job thread, and re-runs go through the scheduler's submit-with-retry path. That avoids the
 * deadlock of occupying the job thread while trying to submit a job to it.
 *
 * <p>The target set is today's constituents plus pool and holdings: the check only ever looks at
 * today (on a non-trading day it returns early), and the weekly constituent sync runs on Saturday,
 * so "current" and "valid on today" are the same set here.
 */
public class CatchUpService {

  /** What today should have versus what it actually has. */
  public record Gap(
      LocalDate expected, boolean tradingDay, int targets, long withBars, long withValuation) {

    public boolean barsMissing() {
      return tradingDay && withBars < targets;
    }

    public boolean valuationMissing() {
      return tradingDay && withValuation < targets;
    }

    public String describe() {
      if (!tradingDay) {
        return expected == null ? "交易日历为空，跳过补偿检查" : "今天非交易日，无需补偿（最近交易日 " + expected + "）";
      }
      return expected + " 补偿检查：目标 " + targets + " 只，K 线 " + withBars + "，估值 " + withValuation;
    }
  }

  private final MarketDataProperties props;
  private final UniverseScope scope;
  private final DailyBarRepository bars;
  private final ValuationRepository valuations;
  private final TradingDayRepository tradingDays;
  private final Clock clock;

  public CatchUpService(
      MarketDataProperties props,
      UniverseScope scope,
      DailyBarRepository bars,
      ValuationRepository valuations,
      TradingDayRepository tradingDays,
      Clock clock) {
    this.props = props;
    this.scope = scope;
    this.bars = bars;
    this.valuations = valuations;
    this.tradingDays = tradingDays;
    this.clock = clock;
  }

  public Gap check() {
    ZonedDateTime now = ZonedDateTime.now(clock.withZone(ZoneId.of(props.zone())));
    LocalDate today = now.toLocalDate();
    LocalDate expected =
        DailyIncrementService.expectedLatestTradingDay(
            tradingDays.between(Market.US, today.minusDays(45), today), now);
    if (expected == null) {
      return new Gap(null, false, 0, 0, 0);
    }
    if (!expected.equals(today)) {
      // Today is not a trading day (weekend or holiday), or its close has not settled yet: nothing
      // to catch up
      return new Gap(expected, false, 0, 0, 0);
    }

    Set<Long> targets = new LinkedHashSet<>();
    scope.universe().forEach(r -> targets.add(r.id()));
    scope.poolAndHoldings().forEach(r -> targets.add(r.id()));
    // The repositories return every instrument with a row on that date; intersect with the targets
    // so rows of non-target instruments (former constituents, ad-hoc symbols) cannot make an
    // incomplete day look complete
    long withBars =
        bars.instrumentIdsWithBarOn(expected).stream().filter(targets::contains).count();
    long withValuation =
        valuations.instrumentIdsOn(expected).stream().filter(targets::contains).count();
    return new Gap(expected, true, targets.size(), withBars, withValuation);
  }
}
