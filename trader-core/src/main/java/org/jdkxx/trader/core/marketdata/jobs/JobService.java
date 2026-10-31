package org.jdkxx.trader.core.marketdata.jobs;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.jdkxx.trader.storage.marketdata.JobRunRepository;
import org.jdkxx.trader.storage.marketdata.JobRunRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Batch job executor: only one job runs at a time (Futu quotas and rate limits are shared across
 * the whole process, so serial execution is the safest), and every run is recorded in job_run.
 *
 * <p>Concurrency: {@link #submit} is synchronized and refuses (rather than queues) while {@link
 * #running} is set, so callers that must not lose a run (scheduled jobs) go through {@code
 * ScheduledSubmitter}'s retry. The job body runs on the single daemon thread {@code
 * marketdata-jobs}; {@link #running} is an {@link AtomicReference} so REST threads can read the
 * current job and its progress without taking the submit lock.
 */
public class JobService {

  private static final Logger log = LoggerFactory.getLogger(JobService.class);

  public record Running(long id, String job, String trigger, Instant startedAt, String progress) {}

  private final JobRunRepository repo;
  private final ExecutorService executor =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "marketdata-jobs");
            t.setDaemon(true);
            return t;
          });
  private final AtomicReference<Running> running = new AtomicReference<>();
  private volatile boolean cancelRequested;

  public JobService(JobRunRepository repo) {
    this.repo = repo;
  }

  /**
   * Rows still RUNNING at startup belong to a previous process that died mid-job (the executor
   * thread is a daemon and is not waited for on shutdown); close them as FAILED so they do not look
   * like a job in progress.
   */
  @PostConstruct
  void failStale() {
    int n = repo.failStale();
    if (n > 0) {
      log.warn("把上次未完成的 {} 条作业记录标为 FAILED", n);
    }
  }

  /**
   * Submits a job; throws IllegalStateException while another job is running. Returns job_run.id.
   */
  public synchronized long submit(String job, String trigger, JobBody body) {
    Running cur = running.get();
    if (cur != null) {
      throw new IllegalStateException("作业「" + cur.job() + "」（#" + cur.id() + "）正在运行，稍后再试");
    }
    long id = repo.start(job, trigger);
    Running r = new Running(id, job, trigger, Instant.now(), "");
    running.set(r);
    cancelRequested = false;
    executor.execute(() -> execute(r, body));
    return id;
  }

  private void execute(Running r, JobBody body) {
    String[] partial = {null};
    JobContext ctx =
        new JobContext() {
          private long lastFlush;

          @Override
          public long id() {
            return r.id();
          }

          @Override
          public void progress(String text) {
            // In-memory progress is always current; the database copy is throttled to one write per
            // 5 seconds because loops call this per instrument
            running.set(new Running(r.id(), r.job(), r.trigger(), r.startedAt(), text));
            long now = System.currentTimeMillis();
            if (now - lastFlush > 5000) {
              lastFlush = now;
              try {
                repo.progress(r.id(), text);
              } catch (RuntimeException e) {
                log.debug("写进度失败：{}", e.toString());
              }
            }
          }

          @Override
          public void partial(String reason) {
            partial[0] = partial[0] == null ? reason : partial[0] + "；" + reason;
          }

          @Override
          public boolean cancelled() {
            return cancelRequested;
          }
        };
    log.info("作业 {} #{} 开始（{}）", r.job(), r.id(), r.trigger());
    // Settle the verdict first, then persist it. With finish inside the try, a job that had
    // succeeded but hit a hiccup while writing its result would be caught by the same catch and
    // recorded as FAILED, and the daily check and health metric would raise a false alarm (found in
    // the 2026-09-25 full-project review).
    String status;
    String text;
    try {
      String summary = body.run(ctx);
      status = partial[0] == null ? "OK" : "PARTIAL";
      text = partial[0] == null ? summary : summary + "；部分失败：" + partial[0];
    } catch (Throwable t) {
      log.error("作业 {} #{} 失败", r.job(), r.id(), t);
      status = "FAILED";
      text = t.toString();
    } finally {
      // Free the slot before persisting, so a slow or failing finish() cannot keep new submissions
      // rejected
      running.set(null);
    }
    try {
      repo.finish(r.id(), status, text);
      log.info("作业 {} #{} {}：{}", r.job(), r.id(), status, text);
    } catch (RuntimeException e) {
      // If it cannot be persisted, leave the row RUNNING for the job health metric to notice; never
      // rewrite a verdict that has already been settled
      log.error("作业 {} #{} 的结果落库失败（作业本身是 {}：{}）", r.job(), r.id(), status, text, e);
    }
  }

  public Optional<Running> current() {
    return Optional.ofNullable(running.get());
  }

  public void cancel() {
    cancelRequested = true;
  }

  public List<JobRunRow> latest(int limit) {
    return repo.latest(limit);
  }

  public Optional<JobRunRow> find(long id) {
    return repo.find(id);
  }

  @PreDestroy
  void shutdown() {
    cancelRequested = true;
    executor.shutdownNow();
  }
}
