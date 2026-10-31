package org.jdkxx.trader.storage.selection;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read-only coverage statistics of quant stock selection over the shared tables (the 6.1 acceptance
 * check of §25.9).
 */
@Repository
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class SelectionCoverageRepository {

  /**
   * Quarterly report depth: among instruments whose reports were backfilled successfully, how many
   * have their earliest income-statement quarter on or before {@code needBy}.
   *
   * @param shallowSamples instruments that are not deep enough (symbol + earliest quarter end); new
   *     listings and spin-offs are naturally short
   */
  public record FinancialDepth(
      long instruments, long deep, LocalDate medianEarliest, List<String> shallowSamples) {}

  private final JdbcTemplate jdbc;

  public SelectionCoverageRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** The CTE text block ends with a newline, so the appended statements start on their own line. */
  public FinancialDepth financialDepth(LocalDate needBy, int sampleLimit) {
    String with =
        """
                WITH d AS (
                    SELECT r.instrument_id, min(r.period_end) AS earliest
                    FROM financial_report r
                    WHERE r.statement = 'INCOME' AND r.period_text ~ '/Q[1-4]$'
                      AND r.instrument_id IN (SELECT instrument_id FROM selection_backfill_state WHERE part = 'FINANCIALS' AND status = 'OK')
                    GROUP BY r.instrument_id)
                """;
    Date need = Date.valueOf(needBy);
    Object[] head =
        jdbc.queryForObject(
            with
                + """
                SELECT count(*) AS n, count(*) FILTER (WHERE earliest <= ?) AS deep,
                       percentile_disc(0.5) WITHIN GROUP (ORDER BY earliest) AS med FROM d""",
            (rs, n) ->
                new Object[] {
                  rs.getLong("n"),
                  rs.getLong("deep"),
                  rs.getDate("med") == null ? null : rs.getDate("med").toLocalDate()
                },
            need);
    List<String> shallow =
        jdbc.query(
            with
                + """
                SELECT i.symbol || ' 自 ' || d.earliest AS s FROM d JOIN instrument i ON i.id = d.instrument_id
                WHERE d.earliest > ? ORDER BY d.earliest DESC, i.symbol LIMIT ?""",
            (rs, n) -> rs.getString("s"),
            need,
            sampleLimit);
    return new FinancialDepth((Long) head[0], (Long) head[1], (LocalDate) head[2], shallow);
  }
}
