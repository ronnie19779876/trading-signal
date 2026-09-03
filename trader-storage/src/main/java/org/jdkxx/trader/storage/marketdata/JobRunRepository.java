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

    public Optional<JobRunRow> find(long id) {
        return jdbc.query("SELECT * FROM job_run WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    /** 应用启动时把上次没跑完的记录标成 FAILED（进程被杀时留下的 RUNNING）。 */
    public int failStale() {
        return jdbc.update("UPDATE job_run SET status = 'FAILED', finished_at = now(), summary = COALESCE(summary, '') || '（进程重启，作业未完成）' WHERE status = 'RUNNING'");
    }
}
