package org.jdkxx.trader.core.marketdata.jobs;

import org.jdkxx.trader.storage.marketdata.JobRunRepository;
import org.jdkxx.trader.storage.marketdata.JobRunRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 跑批执行器：同一时刻只跑一个作业（富途额度与限频都是进程内共享的，串行最稳），每次运行落 job_run。
 */
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    public record Running(long id, String job, String trigger, Instant startedAt, String progress) {
    }

    private final JobRunRepository repo;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "marketdata-jobs");
        t.setDaemon(true);
        return t;
    });
    private final AtomicReference<Running> running = new AtomicReference<>();
    private volatile boolean cancelRequested;

    public JobService(JobRunRepository repo) {
        this.repo = repo;
    }

    @PostConstruct
    void failStale() {
        int n = repo.failStale();
        if (n > 0) {
            log.warn("把上次未完成的 {} 条作业记录标为 FAILED", n);
        }
    }

    /** 提交作业；已有作业在跑时抛 IllegalStateException。返回 job_run.id。 */
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
        JobContext ctx = new JobContext() {
            private long lastFlush;

            @Override
            public long id() {
                return r.id();
            }

            @Override
            public void progress(String text) {
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
        try {
            String summary = body.run(ctx);
            String status = partial[0] == null ? "OK" : "PARTIAL";
            String text = partial[0] == null ? summary : summary + "；部分失败：" + partial[0];
            repo.finish(r.id(), status, text);
            log.info("作业 {} #{} {}：{}", r.job(), r.id(), status, text);
        } catch (Throwable t) {
            log.error("作业 {} #{} 失败", r.job(), r.id(), t);
            repo.finish(r.id(), "FAILED", t.toString());
        } finally {
            running.set(null);
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
