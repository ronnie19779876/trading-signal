package org.jdkxx.trader.storage.marketdata;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class JobRunRepository {

    private static final RowMapper<JobRunRow> MAPPER = (rs, i) -> new JobRunRow(rs.getLong("id"), rs.getString("job"),
            rs.getString("trigger"), rs.getTimestamp("started_at").toInstant(),
            rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant(),
            rs.getString("status"), rs.getString("summary"));

    private final JdbcTemplate jdbc;

    public JobRunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long start(String job, String trigger) {
        return jdbc.queryForObject("INSERT INTO job_run (job, trigger, status) VALUES (?, ?, 'RUNNING') RETURNING id", Long.class, job, trigger);
    }

    public void finish(long id, String status, String summary) {
        jdbc.update("UPDATE job_run SET finished_at = now(), status = ?, summary = ? WHERE id = ?", status, summary, id);
    }

    public void progress(long id, String summary) {
        jdbc.update("UPDATE job_run SET summary = ? WHERE id = ?", summary, id);
    }

    public List<JobRunRow> latest(int limit) {
        return jdbc.query("SELECT * FROM job_run ORDER BY started_at DESC, id DESC LIMIT ?", MAPPER, Math.max(1, Math.min(limit, 200)));
    }

    public Optional<JobRunRow> latestOf(String job) {
        return jdbc.query("SELECT * FROM job_run WHERE job = ? ORDER BY started_at DESC, id DESC LIMIT 1", MAPPER, job).stream().findFirst();
    }

    public Optional<JobRunRow> find(long id) {
        return jdbc.query("SELECT * FROM job_run WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    /**
     * 记一次被放弃的定时作业。
     * 定时作业被丢弃时原本只有一行 WARN 日志，日志随部署清理还会丢，事后完全查不到；
     * 留一行 SKIPPED 才能让健康检查与巡检看见。
     */
    public long skipped(String job, String trigger, String reason) {
        return jdbc.queryForObject(
                "INSERT INTO job_run (job, trigger, status, finished_at, summary) VALUES (?, ?, 'SKIPPED', now(), ?) RETURNING id",
                Long.class, job, trigger, reason);
    }

    /** 每个作业最近一次的运行情况，健康检查用。 */
    public List<JobRunRow> latestPerJob() {
        return jdbc.query("SELECT DISTINCT ON (job) * FROM job_run ORDER BY job, started_at DESC, id DESC", MAPPER);
    }

    /** 指定作业在某个时刻之后有没有成功跑过（OK 或 PARTIAL 都算跑过）。 */
    public boolean succeededSince(String job, java.time.Instant since) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM job_run WHERE job = ? AND started_at >= ? AND status IN ('OK', 'PARTIAL')",
                Integer.class, job, java.sql.Timestamp.from(since));
        return n != null && n > 0;
    }

    /** 应用启动时把上次没跑完的记录标成 FAILED（进程被杀时留下的 RUNNING）。 */
    public int failStale() {
        return jdbc.update("UPDATE job_run SET status = 'FAILED', finished_at = now(), summary = COALESCE(summary, '') || '（进程重启，作业未完成）' WHERE status = 'RUNNING'");
    }
}
