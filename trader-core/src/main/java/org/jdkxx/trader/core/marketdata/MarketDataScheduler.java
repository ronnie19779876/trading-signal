package org.jdkxx.trader.core.marketdata;

import org.jdkxx.trader.core.marketdata.fundamentals.FundamentalsFacade;
import org.jdkxx.trader.core.marketdata.jobs.CatchUpService;
import org.jdkxx.trader.storage.marketdata.JobRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import jakarta.annotation.PreDestroy;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 定时跑批（只在 trader.marketdata.schedule-enabled=true 的实例装配；同一台服务器只允许一个实例开启）。
 * 增量：每个交易日收盘后；估值快照：增量之后 10 分钟；成分股同步：每周六早上；财报：每周六早上七点。
 *
 * <p><b>碰撞重试</b>：作业执行器是单线程，已有作业在跑时提交会抛 {@link IllegalStateException}。
 * 原先只打一行 WARN 就算了——增量与估值只隔 10 分钟，增量实测要 6.5 分钟，
 * 一旦超过 10 分钟，估值就被静默丢弃；而估值是时点数据，当天不补就永远没有。
 * 现在改为延后重试，重试到底仍失败会写一行 SKIPPED 留痕（日志会随部署清理丢掉，库里的不会）。
 */
public class MarketDataScheduler {

    private static final Logger log = LoggerFactory.getLogger(MarketDataScheduler.class);

    /** 碰撞后每隔多久重试一次。 */
    static final Duration RETRY_EVERY = Duration.ofMinutes(5);
    /** 最多重试几次（5 分钟 × 6 = 覆盖半小时）。 */
    static final int RETRY_TIMES = 6;

    private final MarketDataFacade facade;
    private final FundamentalsFacade fundamentals;
    private final JobRunRepository jobRuns;
    private final CatchUpService catchUpService;
    private final ScheduledExecutorService retries = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "marketdata-retry");
        t.setDaemon(true);
        return t;
    });

    public MarketDataScheduler(MarketDataFacade facade, FundamentalsFacade fundamentals, JobRunRepository jobRuns,
                               CatchUpService catchUpService) {
        this.facade = facade;
        this.fundamentals = fundamentals;
        this.jobRuns = jobRuns;
        this.catchUpService = catchUpService;
    }

    @Scheduled(cron = "${trader.marketdata.increment-cron}", zone = "${trader.marketdata.zone}")
    public void increment() {
        submit("每日增量", Jobs.DAILY_INCREMENT, () -> facade.increment("SCHEDULE"));
    }

    @Scheduled(cron = "${trader.marketdata.universe.sync-cron}", zone = "${trader.marketdata.zone}")
    public void weekly() {
        submit("成分股同步", Jobs.UNIVERSE_SYNC, () -> facade.syncUniverse("SCHEDULE"));
    }

    /** 排在每日增量之后：市值与市盈率随价格走，收盘后取到的才是当日值。 */
    @Scheduled(cron = "${trader.marketdata.valuation-cron}", zone = "${trader.marketdata.zone}")
    public void valuation() {
        submit("估值快照", Jobs.VALUATION_SNAPSHOT, () -> fundamentals.refreshValuation("SCHEDULE"));
    }

    @Scheduled(cron = "${trader.marketdata.financials-cron}", zone = "${trader.marketdata.zone}")
    public void financials() {
        submit("财报刷新", Jobs.FINANCIALS_REFRESH, () -> fundamentals.refreshFinancials("SCHEDULE", false));
    }

    /**
     * 当天补偿检查：确认当日数据齐了，缺什么补什么。
     * 排在收盘后较晚的时点，但要早于次日盘前——券商收盘后冻结当前价，过了盘前就取不到当日口径了。
     */
    @Scheduled(cron = "${trader.marketdata.catchup-cron}", zone = "${trader.marketdata.zone}")
    public void catchUp() {
        CatchUpService.Gap gap;
        try {
            gap = catchUpService.check();
        } catch (RuntimeException e) {
            log.error("补偿检查失败：{}", e.toString());
            return;
        }
        if (!gap.tradingDay()) {
            log.info("{}", gap.describe());
            return;
        }
        if (!gap.barsMissing() && !gap.valuationMissing()) {
            log.info("{}；都齐了", gap.describe());
            return;
        }
        log.warn("{}；开始补跑", gap.describe());
        if (gap.barsMissing()) {
            submit("补跑每日增量", Jobs.DAILY_INCREMENT, () -> facade.increment("CATCHUP"));
        }
        if (gap.valuationMissing()) {
            submit("补跑估值快照", Jobs.VALUATION_SNAPSHOT, () -> fundamentals.refreshValuation("CATCHUP"));
        }
    }

    private void submit(String what, String job, LongSupplier action) {
        attempt(what, job, action, 0);
    }

    /** 提交一次；被占用就排下一次重试，次数用尽写 SKIPPED。重试跑在自己的线程上，不占作业线程。 */
    private void attempt(String what, String job, LongSupplier action, int tried) {
        try {
            long id = action.getAsLong();
            log.info("定时触发{}，作业 #{}{}", what, id, tried > 0 ? "（第 " + (tried + 1) + " 次尝试）" : "");
        } catch (RuntimeException e) {
            String reason = e.getMessage() == null ? e.toString() : e.getMessage();
            if (tried + 1 >= RETRY_TIMES) {
                log.error("定时触发{}最终放弃（已试 {} 次）：{}", what, tried + 1, reason);
                record(job, "重试 " + (tried + 1) + " 次仍无法提交：" + reason);
                return;
            }
            log.warn("定时触发{}失败，{} 分钟后重试（第 {}/{} 次）：{}",
                    what, RETRY_EVERY.toMinutes(), tried + 1, RETRY_TIMES, reason);
            try {
                retries.schedule(() -> attempt(what, job, action, tried + 1), RETRY_EVERY.toMillis(), TimeUnit.MILLISECONDS);
            } catch (RuntimeException scheduleFailed) {
                log.error("排重试失败（{}）：{}", what, scheduleFailed.toString());
                record(job, "无法排重试：" + scheduleFailed);
            }
        }
    }

    /** 留痕失败不能连累调度线程。 */
    private void record(String job, String reason) {
        try {
            jobRuns.skipped(job, "SCHEDULE", reason);
        } catch (RuntimeException e) {
            log.warn("写 SKIPPED 记录失败：{}", e.toString());
        }
    }

    @PreDestroy
    void shutdown() {
        retries.shutdownNow();
    }
}
