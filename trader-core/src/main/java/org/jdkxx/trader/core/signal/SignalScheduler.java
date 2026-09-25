package org.jdkxx.trader.core.signal;

import jakarta.annotation.PreDestroy;
import org.jdkxx.trader.core.marketdata.Jobs;
import org.jdkxx.trader.core.marketdata.jobs.ScheduledSubmitter;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.signal.SentinelThresholds;
import org.jdkxx.trader.storage.marketdata.JobRunRepository;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;
import org.jdkxx.trader.storage.signal.SignalEvaluationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 信号评估的定时任务（只在 schedule-enabled 且 trader.signal.enabled 的实例装配）。
 * 美东 18:10 评估当天；22:00 补偿检查：当天没有评估，或有判为数据过期、但现在库里已经有当天 K 线的标的（21:00 行情补跑补齐了），就重跑。
 * 碰撞重试与 SKIPPED 留痕沿用 {@link ScheduledSubmitter}。
 */
public class SignalScheduler {

    private static final Logger log = LoggerFactory.getLogger(SignalScheduler.class);

    private final SignalFacade facade;
    private final SignalEvaluationRepository evaluations;
    private final TradingDayRepository days;
    private final Clock clock;
    private final ZoneId zone;
    private final ScheduledSubmitter submitter;

    public SignalScheduler(SignalFacade facade, SignalEvaluationRepository evaluations, TradingDayRepository days,
                           JobRunRepository jobRuns, Clock clock, ZoneId zone) {
        this.facade = facade;
        this.evaluations = evaluations;
        this.days = days;
        this.clock = clock;
        this.zone = zone;
        this.submitter = new ScheduledSubmitter(jobRuns, "signal-retry");
    }

    @Scheduled(cron = "${trader.signal.evaluation-cron}", zone = "${trader.marketdata.zone}")
    public void evaluate() {
        LocalDate today = LocalDate.now(clock.withZone(zone));
        if (!days.isTradingDay(Market.US, today)) {
            log.info("{} 非交易日，不做信号评估", today);
            return;
        }
        submitter.submit("信号评估", Jobs.SIGNAL_EVALUATION, "SCHEDULE", () -> facade.evaluate("SCHEDULE", today));
    }

    @Scheduled(cron = "${trader.signal.catchup-cron}", zone = "${trader.marketdata.zone}")
    public void catchUp() {
        LocalDate today = LocalDate.now(clock.withZone(zone));
        String version = SentinelThresholds.V1.version();
        try {
            if (!days.isTradingDay(Market.US, today)) {
                return;
            }
            boolean missing = evaluations.on(today, version).isEmpty();
            long refillable = missing ? 0 : evaluations.staleButNowHasBar(today, version);
            if (!missing && refillable == 0) {
                log.info("{} 信号评估齐了，无需补跑", today);
                return;
            }
            log.warn("{} 信号评估{}，补跑", today, missing ? "缺失" : "有 " + refillable + " 只当时数据过期、现已补齐");
        } catch (RuntimeException e) {
            log.error("信号补偿检查失败：{}", e.toString());
            return;
        }
        submitter.submit("补跑信号评估", Jobs.SIGNAL_EVALUATION, "CATCHUP", () -> facade.evaluate("CATCHUP", today));
    }

    @PreDestroy
    void shutdown() {
        submitter.close();
    }
}
