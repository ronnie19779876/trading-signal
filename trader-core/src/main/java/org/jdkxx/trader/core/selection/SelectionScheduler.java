package org.jdkxx.trader.core.selection;

import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.jdkxx.trader.core.marketdata.jobs.ScheduledSubmitter;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.selection.SelectionModel;
import org.jdkxx.trader.storage.marketdata.JobRunRepository;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;
import org.jdkxx.trader.storage.selection.SelectionScoreRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Scheduled jobs for quant selection (wired only on instances with schedule-enabled and
 * trader.selection.enabled). 18:30 US/Eastern daily scoring (after the 17:40 valuation snapshot and
 * the 18:10 signal evaluation; the simulated-trading recompute follows the scoring); 22:30
 * catch-up: rerun when today has no scores or the simulated trades were not computed through today;
 * Saturday 09:00 refresh of announcement dates and security types, 10:00 simulated-trading weekly
 * report. Collision retries and SKIPPED records reuse {@link ScheduledSubmitter}. All four crons
 * must be present in the in-jar application.yml (ScheduledPlaceholdersTest).
 */
public class SelectionScheduler {

  private static final Logger log = LoggerFactory.getLogger(SelectionScheduler.class);

  private final SelectionFacade facade;
  private final SelectionScoreRepository scores;
  private final SelectionSimService sim;
  private final TradingDayRepository days;
  private final Clock clock;
  private final ZoneId zone;
  private final ScheduledSubmitter submitter;

  public SelectionScheduler(
      SelectionFacade facade,
      SelectionScoreRepository scores,
      SelectionSimService sim,
      TradingDayRepository days,
      JobRunRepository jobRuns,
      Clock clock,
      ZoneId zone) {
    this.facade = facade;
    this.scores = scores;
    this.sim = sim;
    this.days = days;
    this.clock = clock;
    this.zone = zone;
    this.submitter = new ScheduledSubmitter(jobRuns, "selection-retry");
  }

  @Scheduled(cron = "${trader.selection.daily-cron}", zone = "${trader.marketdata.zone}")
  public void daily() {
    // The cron fires in US/Eastern; "today" must be taken in the same zone
    LocalDate today = LocalDate.now(clock.withZone(zone));
    if (!days.isTradingDay(Market.US, today)) {
      log.info("{} 非交易日，不做量化选股评分", today);
      return;
    }
    submitter.submit(
        "量化选股每日评分", SelectionJobs.DAILY, "SCHEDULE", () -> facade.daily("SCHEDULE", today));
  }

  @Scheduled(cron = "${trader.selection.catchup-cron}", zone = "${trader.marketdata.zone}")
  public void catchUp() {
    LocalDate today = LocalDate.now(clock.withZone(zone));
    try {
      if (!days.isTradingDay(Market.US, today)) {
        return;
      }
      boolean scored =
          scores.latestDate(SelectionModel.VERSION).filter(d -> !d.isBefore(today)).isPresent();
      boolean simStale = sim.stale(today);
      if (scored && !simStale) {
        log.info("{} 量化选股评分与模拟交易都齐了，无需补跑", today);
        return;
      }
      log.warn("{} 量化选股{}，补跑", today, scored ? "模拟交易没算到当天" : "评分缺失");
    } catch (RuntimeException e) {
      log.error("量化选股补偿检查失败：{}", e.toString());
      return;
    }
    submitter.submit(
        "补跑量化选股评分", SelectionJobs.DAILY, "CATCHUP", () -> facade.daily("CATCHUP", today));
  }

  @Scheduled(cron = "${trader.selection.weekly-cron}", zone = "${trader.marketdata.zone}")
  public void weekly() {
    submitter.submit("量化选股周刷新", SelectionJobs.WEEKLY, "SCHEDULE", () -> facade.weekly("SCHEDULE"));
  }

  @Scheduled(cron = "${trader.selection.sim-weekly-cron}", zone = "${trader.marketdata.zone}")
  public void simWeekly() {
    submitter.submit(
        "模拟交易周报",
        SelectionJobs.SIM_WEEKLY,
        "SCHEDULE",
        () -> facade.simWeekly("SCHEDULE", null, false));
  }

  @PreDestroy
  void shutdown() {
    submitter.close();
  }
}
