package org.jdkxx.trader.core.account;

import org.jdkxx.trader.core.marketdata.Jobs;
import org.jdkxx.trader.core.marketdata.jobs.ScheduledSubmitter;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.storage.account.AccountSnapshotRepository;
import org.jdkxx.trader.storage.marketdata.JobRunRepository;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import jakarta.annotation.PreDestroy;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 账户快照的定时任务（只在 schedule-enabled 且 trader.account.enabled 的实例装配）。
 * 美东 18:00 拍当天快照；21:00 与行情补偿检查同一时点，当天没有快照就补拍（仍在快照窗口内）。
 * 碰撞重试与 SKIPPED 留痕沿用 {@link ScheduledSubmitter}。
 */
public class AccountScheduler {

    private static final Logger log = LoggerFactory.getLogger(AccountScheduler.class);

    private final AccountFacade facade;
    private final AccountSnapshotRepository snapshots;
    private final TradingDayRepository days;
    private final Clock clock;
    private final ZoneId zone;
    private final ScheduledSubmitter submitter;

    public AccountScheduler(AccountFacade facade, AccountSnapshotRepository snapshots, TradingDayRepository days,
                            JobRunRepository jobRuns, Clock clock, ZoneId zone) {
        this.facade = facade;
        this.snapshots = snapshots;
        this.days = days;
        this.clock = clock;
        this.zone = zone;
        this.submitter = new ScheduledSubmitter(jobRuns, "account-retry");
    }

    @Scheduled(cron = "${trader.account.snapshot-cron}", zone = "${trader.marketdata.zone}")
    public void snapshot() {
        LocalDate today = LocalDate.now(clock.withZone(zone));
        if (!days.isTradingDay(Market.US, today)) {
            log.info("{} 非交易日，不拍账户快照", today);
            return;
        }
        submitter.submit("账户快照", Jobs.ACCOUNT_SNAPSHOT, () -> facade.snapshot("SCHEDULE", false));
    }

    @Scheduled(cron = "${trader.marketdata.catchup-cron}", zone = "${trader.marketdata.zone}")
    public void catchUp() {
        LocalDate today = LocalDate.now(clock.withZone(zone));
        try {
            if (!days.isTradingDay(Market.US, today)) {
                return;
            }
            if (snapshots.existsOn(today)) {
                log.info("{} 账户快照已存在，无需补拍", today);
                return;
            }
        } catch (RuntimeException e) {
            log.error("账户快照补偿检查失败：{}", e.toString());
            return;
        }
        log.warn("{} 没有账户快照，补拍", today);
        submitter.submit("补拍账户快照", Jobs.ACCOUNT_SNAPSHOT, () -> facade.snapshot("CATCHUP", false));
    }

    @PreDestroy
    void shutdown() {
        submitter.close();
    }
}
