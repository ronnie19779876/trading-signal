package org.jdkxx.trader.core.marketdata.jobs;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.jdkxx.trader.storage.marketdata.JobRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Submission of scheduled jobs with collision retry: the job executor is single-threaded, and
 * submitting while a job is running throws {@link IllegalStateException}. When busy it retries
 * every {@link #RETRY_EVERY}, at most {@link #RETRY_TIMES} attempts in total; if the retries run
 * out it writes one SKIPPED row as a trace (logs are lost when deployments clean up, the database
 * row is not). Retries run on their own thread and occupy neither the job thread nor the scheduler
 * thread.
 *
 * <p>Any {@link RuntimeException} from the submit action is treated as retryable, not only the busy
 * case; the reason text ends up in the SKIPPED row either way.
 *
 * <p>Extracted from {@code MarketDataScheduler}; the account snapshot schedule uses it too.
 */
public class ScheduledSubmitter implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(ScheduledSubmitter.class);

  /** How long to wait between retries after a collision. */
  public static final Duration RETRY_EVERY = Duration.ofMinutes(5);

  /** Maximum number of attempts (5 minutes × 6 = covers half an hour). */
  public static final int RETRY_TIMES = 6;

  private final JobRunRepository jobRuns;
  private final Duration retryEvery;
  private final ScheduledExecutorService retries;

  public ScheduledSubmitter(JobRunRepository jobRuns, String threadName) {
    this(jobRuns, threadName, RETRY_EVERY);
  }

  /** For tests: shorter retry interval. */
  ScheduledSubmitter(JobRunRepository jobRuns, String threadName, Duration retryEvery) {
    this.jobRuns = jobRuns;
    this.retryEvery = retryEvery;
    this.retries =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, threadName);
              t.setDaemon(true);
              return t;
            });
  }

  /**
   * @param trigger trigger source recorded in the trace (SCHEDULE / CATCHUP / …). {@link #record}
   *     used to hard-code it as "SCHEDULE", so a job submitted by the catch-up check that was given
   *     up on was also recorded as a scheduled trigger, and afterwards nobody could tell which path
   *     it came from (found in the 2026-09-25 full-project review).
   */
  public void submit(String what, String job, String trigger, LongSupplier action) {
    attempt(what, job, trigger, action, 0);
  }

  /**
   * One attempt; when busy, schedule the next retry; when attempts are exhausted, write SKIPPED.
   * {@code tried} is the number of failed attempts so far, so the first call runs with 0 and the
   * last with RETRY_TIMES − 1.
   */
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
      log.warn(
          "定时触发{}失败，{} 秒后重试（第 {}/{} 次）：{}",
          what,
          retryEvery.toSeconds(),
          tried + 1,
          RETRY_TIMES,
          reason);
      try {
        retries.schedule(
            () -> attempt(what, job, trigger, action, tried + 1),
            retryEvery.toMillis(),
            TimeUnit.MILLISECONDS);
      } catch (RuntimeException scheduleFailed) {
        log.error("排重试失败（{}）：{}", what, scheduleFailed.toString());
        record(job, trigger, "无法排重试：" + scheduleFailed);
      }
    }
  }

  /** Failing to write the trace must not take the scheduler thread down with it. */
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
