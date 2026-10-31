package org.jdkxx.trader.storage.selection;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * Runs of the quant selection historical test (ARCHITECTURE §30, V20). Append-only: a run is
 * written once, when it has finished, and never changed. Every holdout run stays on record so the
 * report can say how many times the holdout has been used.
 */
@Repository
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class SelectionBacktestRunRepository {

  /** One run without its result body (for listings). */
  public record RunSummary(
      long id,
      String spec,
      boolean candidate,
      LocalDate sampleFrom,
      LocalDate sampleTo,
      boolean holdout,
      Long jobRunId,
      Instant createdAt) {}

  /** A run with its spec and result as JSON text. */
  public record Run(RunSummary summary, String specDetail, String result) {}

  private static final String SUMMARY_COLUMNS =
      "id, spec, candidate, sample_from, sample_to, holdout, job_run_id, created_at";

  private static final RowMapper<RunSummary> SUMMARY =
      (rs, i) ->
          new RunSummary(
              rs.getLong("id"),
              rs.getString("spec"),
              rs.getBoolean("candidate"),
              rs.getDate("sample_from").toLocalDate(),
              rs.getDate("sample_to").toLocalDate(),
              rs.getBoolean("holdout"),
              rs.getObject("job_run_id") == null ? null : rs.getLong("job_run_id"),
              rs.getTimestamp("created_at").toInstant());

  private final JdbcTemplate jdbc;

  public SelectionBacktestRunRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public long insert(
      String spec,
      boolean candidate,
      String specDetail,
      LocalDate from,
      LocalDate to,
      boolean holdout,
      Long jobRunId,
      String result) {
    GeneratedKeyHolder key = new GeneratedKeyHolder();
    jdbc.update(
        con -> {
          PreparedStatement ps =
              con.prepareStatement(
                  "INSERT INTO selection_backtest_run (spec, candidate, spec_detail, sample_from,"
                      + " sample_to, holdout, job_run_id, result)"
                      + " VALUES (?, ?, ?::jsonb, ?, ?, ?, ?, ?::jsonb)",
                  Statement.RETURN_GENERATED_KEYS);
          ps.setString(1, spec);
          ps.setBoolean(2, candidate);
          ps.setString(3, specDetail);
          ps.setDate(4, Date.valueOf(from));
          ps.setDate(5, Date.valueOf(to));
          ps.setBoolean(6, holdout);
          ps.setObject(7, jobRunId);
          ps.setString(8, result);
          return ps;
        },
        key);
    return ((Number) Objects.requireNonNull(key.getKeys()).get("id")).longValue();
  }

  /** Newest first; limit is clamped to 1~500. */
  public List<RunSummary> list(int limit) {
    return jdbc.query(
        "SELECT " + SUMMARY_COLUMNS + " FROM selection_backtest_run ORDER BY id DESC LIMIT ?",
        SUMMARY,
        Math.clamp(limit, 1, 500));
  }

  public Optional<Run> find(long id) {
    return jdbc
        .query(
            "SELECT "
                + SUMMARY_COLUMNS
                + ", spec_detail::text AS spec_detail, result::text AS result"
                + " FROM selection_backtest_run WHERE id = ?",
            (rs, i) ->
                new Run(SUMMARY.mapRow(rs, i), rs.getString("spec_detail"), rs.getString("result")),
            id)
        .stream()
        .findFirst();
  }

  /** Number of runs that used the holdout so far. */
  public int holdoutUses() {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM selection_backtest_run WHERE holdout", Integer.class);
    return n == null ? 0 : n;
  }

  /** Distinct candidate specs ever run on the training segment (diagnostic specs not counted). */
  public List<String> candidatesTried() {
    return jdbc.queryForList(
        "SELECT DISTINCT spec FROM selection_backtest_run WHERE candidate AND NOT holdout"
            + " ORDER BY spec",
        String.class);
  }

  /** Runs per spec, split by segment: [spec, training runs, holdout runs]. */
  public List<Object[]> usage() {
    return jdbc.query(
        "SELECT spec, count(*) FILTER (WHERE NOT holdout) AS train,"
            + " count(*) FILTER (WHERE holdout) AS hold FROM selection_backtest_run GROUP BY spec",
        (rs, i) -> new Object[] {rs.getString("spec"), rs.getInt("train"), rs.getInt("hold")});
  }
}
