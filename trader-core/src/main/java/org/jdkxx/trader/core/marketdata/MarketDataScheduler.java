package org.jdkxx.trader.core.marketdata;

import jakarta.annotation.PreDestroy;
import org.jdkxx.trader.core.marketdata.fundamentals.FundamentalsFacade;
import org.jdkxx.trader.core.marketdata.jobs.CatchUpService;
import org.jdkxx.trader.core.marketdata.jobs.ScheduledSubmitter;
import org.jdkxx.trader.storage.marketdata.JobRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Scheduled batch jobs (wired only on an instance with trader.marketdata.schedule-enabled=true;
 * only one instance per server may enable it). Increment: after the close on every trading day;
 * valuation snapshot: 10 minutes after the increment; constituent sync: Saturday morning; financial
 * statements: Saturday 07:00. Every cron is evaluated in the configured zone (US/Eastern), so DST
 * changes are handled by Spring, not by shifting the cron.
 *
 * <p><b>Collision retry</b>: the job executor is single-threaded and submitting while another job
 * runs throws. This used to log one WARN and give up. The increment and the valuation are only 10
 * minutes apart and the increment measured 6.5 minutes; once it ran past 10 minutes the valuation
 * was silently dropped, and since valuation is point-in-time data, a day not caught up is lost
 * forever. Submission now retries later, and when the retries are exhausted it writes a SKIPPED row
 * as a trace; see {@link ScheduledSubmitter}.
 *
 * <p>The cron methods run on Spring's scheduler thread and only submit; the work itself runs on the
 * job thread ({@code JobService}) and retries on the submitter's own thread, so a slow job never
 * blocks other crons.
 */
public class MarketDataScheduler {

  private static final Logger log = LoggerFactory.getLogger(MarketDataScheduler.class);

  private final MarketDataFacade facade;
  private final FundamentalsFacade fundamentals;
  private final JobRunRepository jobRuns;
  private final CatchUpService catchUpService;
  private final ScheduledSubmitter submitter;

  public MarketDataScheduler(
      MarketDataFacade facade,
      FundamentalsFacade fundamentals,
      JobRunRepository jobRuns,
      CatchUpService catchUpService) {
    this.facade = facade;
    this.fundamentals = fundamentals;
    this.jobRuns = jobRuns;
    this.catchUpService = catchUpService;
    this.submitter = new ScheduledSubmitter(jobRuns, "marketdata-retry");
  }

  @Scheduled(cron = "${trader.marketdata.increment-cron}", zone = "${trader.marketdata.zone}")
  public void increment() {
    submitter.submit("每日增量", Jobs.DAILY_INCREMENT, "SCHEDULE", () -> facade.increment("SCHEDULE"));
  }

  @Scheduled(cron = "${trader.marketdata.universe.sync-cron}", zone = "${trader.marketdata.zone}")
  public void weekly() {
    submitter.submit(
        "成分股同步", Jobs.UNIVERSE_SYNC, "SCHEDULE", () -> facade.syncUniverse("SCHEDULE"));
  }

  /**
   * Runs after the daily increment: market cap and P/E move with the price, so only values taken
   * after the close represent that day.
   */
  @Scheduled(cron = "${trader.marketdata.valuation-cron}", zone = "${trader.marketdata.zone}")
  public void valuation() {
    submitter.submit(
        "估值快照",
        Jobs.VALUATION_SNAPSHOT,
        "SCHEDULE",
        () -> fundamentals.refreshValuation("SCHEDULE"));
  }

  @Scheduled(cron = "${trader.marketdata.financials-cron}", zone = "${trader.marketdata.zone}")
  public void financials() {
    submitter.submit(
        "财报刷新",
        Jobs.FINANCIALS_REFRESH,
        "SCHEDULE",
        () -> fundamentals.refreshFinancials("SCHEDULE", false));
  }

  /**
   * Same-day catch-up check: confirm today's data is complete and re-run whatever is missing.
   * Scheduled fairly late after the close but before the next pre-market: the broker freezes the
   * current price after the close, and once pre-market starts the same-day figures can no longer be
   * obtained.
   *
   * <p>Every run writes one {@link Jobs#CATCHUP_CHECK} row (also on non-trading days and when
   * nothing is missing). In 1.1.x a complete day only produced a log line, so if the check itself
   * stopped running there was no trace in the database and the health metric could not see it, even
   * though it is the last line of defense for valuation data.
   */
  @Scheduled(cron = "${trader.marketdata.catchup-cron}", zone = "${trader.marketdata.zone}")
  public void catchUp() {
    CatchUpService.Gap gap;
    try {
      gap = catchUpService.check();
    } catch (RuntimeException e) {
      log.error("补偿检查失败：{}", e.toString());
      note("FAILED", "检查失败：" + e);
      return;
    }
    if (!gap.tradingDay()) {
      log.info("{}", gap.describe());
      note("OK", gap.describe());
      return;
    }
    if (!gap.barsMissing() && !gap.valuationMissing()) {
      log.info("{}；都齐了", gap.describe());
      note("OK", gap.describe() + "；都齐了");
      return;
    }
    log.warn("{}；开始补跑", gap.describe());
    note(
        "OK",
        gap.describe()
            + "；已提交补跑："
            + (gap.barsMissing() ? "每日增量 " : "")
            + (gap.valuationMissing() ? "估值快照" : ""));
    if (gap.barsMissing()) {
      submitter.submit(
          "补跑每日增量", Jobs.DAILY_INCREMENT, "CATCHUP", () -> facade.increment("CATCHUP"));
    }
    if (gap.valuationMissing()) {
      submitter.submit(
          "补跑估值快照",
          Jobs.VALUATION_SNAPSHOT,
          "CATCHUP",
          () -> fundamentals.refreshValuation("CATCHUP"));
    }
  }

  /**
   * Run record of the catch-up check; failing to write it must not block the re-runs that follow.
   */
  private void note(String status, String summary) {
    try {
      jobRuns.record(Jobs.CATCHUP_CHECK, "SCHEDULE", status, summary);
    } catch (RuntimeException e) {
      log.warn("写补偿检查记录失败：{}", e.toString());
    }
  }

  @PreDestroy
  void shutdown() {
    submitter.close();
  }
}
