package org.jdkxx.trader.core.selection;

import jakarta.annotation.PreDestroy;
import org.jdkxx.trader.core.marketdata.jobs.ScheduledSubmitter;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.selection.SelectionModel;
import org.jdkxx.trader.storage.marketdata.JobRunRepository;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;
import org.jdkxx.trader.storage.selection.SelectionScoreRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 量化选股的定时任务（只在 schedule-enabled 且 trader.selection.enabled 的实例装配）。
 * 美东 18:30 每日评分（排在 17:40 估值快照、18:10 信号评估之后）；22:30 补偿：当天没有评分就重跑；周六 09:00 刷新公告日与证券类型。
 * 碰撞重试与 SKIPPED 留痕沿用 {@link ScheduledSubmitter}。三个 cron 必须写进 jar 内 application.yml（ScheduledPlaceholdersTest）。
 */
public class SelectionScheduler {

    private static final Logger log = LoggerFactory.getLogger(SelectionScheduler.class);

    private final SelectionFacade facade;
    private final SelectionScoreRepository scores;
    private final TradingDayRepository days;
    private final Clock clock;
    private final ZoneId zone;
    private final ScheduledSubmitter submitter;

    public SelectionScheduler(SelectionFacade facade, SelectionScoreRepository scores, TradingDayRepository days,
                              JobRunRepository jobRuns, Clock clock, ZoneId zone) {
        this.facade = facade;
        this.scores = scores;
        this.days = days;
        this.clock = clock;
        this.zone = zone;
        this.submitter = new ScheduledSubmitter(jobRuns, "selection-retry");
    }

    @Scheduled(cron = "${trader.selection.daily-cron}", zone = "${trader.marketdata.zone}")
    public void daily() {
        LocalDate today = LocalDate.now(clock.withZone(zone));
        if (!days.isTradingDay(Market.US, today)) {
            log.info("{} 非交易日，不做量化选股评分", today);
            return;
        }
        submitter.submit("量化选股每日评分", SelectionJobs.DAILY, "SCHEDULE", () -> facade.daily("SCHEDULE", today));
    }

    @Scheduled(cron = "${trader.selection.catchup-cron}", zone = "${trader.marketdata.zone}")
    public void catchUp() {
        LocalDate today = LocalDate.now(clock.withZone(zone));
        try {
            if (!days.isTradingDay(Market.US, today)) {
                return;
            }
            if (scores.latestDate(SelectionModel.VERSION).filter(d -> !d.isBefore(today)).isPresent()) {
                log.info("{} 量化选股评分齐了，无需补跑", today);
                return;
            }
            log.warn("{} 量化选股评分缺失，补跑", today);
        } catch (RuntimeException e) {
            log.error("量化选股补偿检查失败：{}", e.toString());
            return;
        }
        submitter.submit("补跑量化选股评分", SelectionJobs.DAILY, "CATCHUP", () -> facade.daily("CATCHUP", today));
    }

    @Scheduled(cron = "${trader.selection.weekly-cron}", zone = "${trader.marketdata.zone}")
    public void weekly() {
        submitter.submit("量化选股周刷新", SelectionJobs.WEEKLY, "SCHEDULE", () -> facade.weekly("SCHEDULE"));
    }

    @PreDestroy
    void shutdown() {
        submitter.close();
    }
}
