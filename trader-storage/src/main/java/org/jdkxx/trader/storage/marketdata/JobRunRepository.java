package org.jdkxx.trader.storage.marketdata;

import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Batch job runs ({@code job_run}): one row per run, RUNNING until finished. The health check and
 * the daily check read these rows, so even skipped or out-of-band runs leave a row.
 */
@Repository
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class JobRunRepository {

  private static final RowMapper<JobRunRow> MAPPER =
      (rs, i) ->
          new JobRunRow(
              rs.getLong("id"),
              rs.getString("job"),
              rs.getString("trigger"),
              rs.getTimestamp("started_at").toInstant(),
              rs.getTimestamp("finished_at") == null
                  ? null
                  : rs.getTimestamp("finished_at").toInstant(),
              rs.getString("status"),
              rs.getString("summary"));

  private final JdbcTemplate jdbc;

  public JobRunRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public long start(String job, String trigger) {
    return jdbc.queryForObject(
        "INSERT INTO job_run (job, trigger, status) VALUES (?, ?, 'RUNNING') RETURNING id",
        Long.class,
        job,
        trigger);
  }

  public void finish(long id, String status, String summary) {
    jdbc.update(
        "UPDATE job_run SET finished_at = now(), status = ?, summary = ? WHERE id = ?",
        status,
        summary,
        id);
  }

  public void progress(long id, String summary) {
    jdbc.update("UPDATE job_run SET summary = ? WHERE id = ?", summary, id);
  }

  public List<JobRunRow> latest(int limit) {
    return jdbc.query(
        "SELECT * FROM job_run ORDER BY started_at DESC, id DESC LIMIT ?",
        MAPPER,
        Math.max(1, Math.min(limit, 200)));
  }

  public Optional<JobRunRow> latestOf(String job) {
    return jdbc
        .query(
            "SELECT * FROM job_run WHERE job = ? ORDER BY started_at DESC, id DESC LIMIT 1",
            MAPPER,
            job)
        .stream()
        .findFirst();
  }

  public Optional<JobRunRow> find(long id) {
    return jdbc.query("SELECT * FROM job_run WHERE id = ?", MAPPER, id).stream().findFirst();
  }

  /**
   * Records a scheduled job that was given up. A dropped scheduled job used to leave only a WARN
   * log line, and logs get cleaned up with deployments, so afterwards there was no trace at all; a
   * SKIPPED row is what lets the health check and the daily check see it.
   */
  public long skipped(String job, String trigger, String reason) {
    return record(job, trigger, "SKIPPED", reason);
  }

  /**
   * Records a run that is already finished (starts and ends at once). For checks that do not go
   * through the job thread but whose runs still need a trace, e.g. the same-day catch-up check: if
   * it stalls and leaves nothing in the database, the health indicator has no way of knowing.
   */
  public long record(String job, String trigger, String status, String summary) {
    return jdbc.queryForObject(
        "INSERT INTO job_run (job, trigger, status, finished_at, summary) VALUES (?, ?, ?, now(), ?) RETURNING id",
        Long.class,
        job,
        trigger,
        status,
        summary);
  }

  /** The latest run of each job (PostgreSQL {@code DISTINCT ON}), for the health check. */
  public List<JobRunRow> latestPerJob() {
    return jdbc.query(
        "SELECT DISTINCT ON (job) * FROM job_run ORDER BY job, started_at DESC, id DESC", MAPPER);
  }

  /**
   * Whether the job has run successfully since the given instant (OK and PARTIAL both count as
   * having run).
   */
  public boolean succeededSince(String job, java.time.Instant since) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM job_run WHERE job = ? AND started_at >= ? AND status IN ('OK', 'PARTIAL')",
            Integer.class,
            job,
            java.sql.Timestamp.from(since));
    return n != null && n > 0;
  }

  /**
   * At application startup, marks runs left unfinished last time as FAILED (RUNNING rows left
   * behind when the process was killed). This touches every RUNNING row in the database, which is
   * only correct because exactly one instance runs against each database (enforced by the
   * environment guard and the standby check).
   */
  public int failStale() {
    return jdbc.update(
        "UPDATE job_run SET status = 'FAILED', finished_at = now(), summary = COALESCE(summary, '') || '（进程重启，作业未完成）' WHERE status = 'RUNNING'");
  }
}
