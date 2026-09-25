package org.jdkxx.trader.core.marketdata.jobs;

import org.jdkxx.trader.storage.marketdata.JobRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 定时触发的作业提交，带碰撞重试：作业执行器是单线程，已有作业在跑时提交会抛 {@link IllegalStateException}。
 * 被占用就每隔 {@link #RETRY_EVERY} 重试，最多 {@link #RETRY_TIMES} 次；重试到底仍失败写一行 SKIPPED 留痕
 * （日志会随部署清理丢掉，库里的不会）。重试跑在自己的线程上，不占作业线程与调度线程。
 *
 * <p>从 {@code MarketDataScheduler} 抽出来，账户快照的调度也用它。
 */
public class ScheduledSubmitter implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ScheduledSubmitter.class);

    /** 碰撞后每隔多久重试一次。 */
    public static final Duration RETRY_EVERY = Duration.ofMinutes(5);
    /** 最多尝试几次（5 分钟 × 6 = 覆盖半小时）。 */
    public static final int RETRY_TIMES = 6;

    private final JobRunRepository jobRuns;
    private final Duration retryEvery;
    private final ScheduledExecutorService retries;

    public ScheduledSubmitter(JobRunRepository jobRuns, String threadName) {
        this(jobRuns, threadName, RETRY_EVERY);
    }

    /** 测试用：缩短重试间隔。 */
    ScheduledSubmitter(JobRunRepository jobRuns, String threadName, Duration retryEvery) {
        this.jobRuns = jobRuns;
        this.retryEvery = retryEvery;
        this.retries = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, threadName);
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * @param trigger 留痕用的触发来源（SCHEDULE / CATCHUP / …）。
     *     原先 {@link #record} 把它硬编码成 "SCHEDULE"，补偿检查提交的作业被放弃时也记成定时触发，
     *     事后分不出是哪一路（2026-09-25 全项目审查发现）。
     */
    public void submit(String what, String job, String trigger, LongSupplier action) {
        attempt(what, job, trigger, action, 0);
    }

    /** 提交一次；被占用就排下一次重试，次数用尽写 SKIPPED。 */
    private void attempt(String what, String job, String trigger, LongSupplier action, int tried) {
        try {
            long id = action.getAsLong();
            log.info("定时触发{}，作业 #{}{}", what, id, tried > 0 ? "（第 " + (tried + 1) + " 次尝试）" : "");
        } catch (RuntimeException e) {
            String reason = e.getMessage() == null ? e.toString() : e.getMessage();
            if (tried + 1 >= RETRY_TIMES) {
                log.error("定时触发{}最终放弃（已试 {} 次）：{}", what, tried + 1, reason);
                record(job, trigger, "重试 " + (tried + 1) + " 次仍无法提交：" + reason);
                return;
            }
            log.warn("定时触发{}失败，{} 秒后重试（第 {}/{} 次）：{}",
                    what, retryEvery.toSeconds(), tried + 1, RETRY_TIMES, reason);
            try {
                retries.schedule(() -> attempt(what, job, trigger, action, tried + 1), retryEvery.toMillis(), TimeUnit.MILLISECONDS);
            } catch (RuntimeException scheduleFailed) {
                log.error("排重试失败（{}）：{}", what, scheduleFailed.toString());
                record(job, trigger, "无法排重试：" + scheduleFailed);
            }
        }
    }

    /** 留痕失败不能连累调度线程。 */
    private void record(String job, String trigger, String reason) {
        try {
            jobRuns.skipped(job, trigger, reason);
        } catch (RuntimeException e) {
            log.warn("写 SKIPPED 记录失败：{}", e.toString());
        }
    }

    @Override
    public void close() {
        retries.shutdownNow();
    }
}
