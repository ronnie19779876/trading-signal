package org.jdkxx.trader.storage.marketdata;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jdkxx.trader.domain.FinancialReport;
import org.jdkxx.trader.domain.FinancialStatement;
import org.jdkxx.trader.domain.Instrument;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Financial report periods and their line items. The unique key includes the period label: the
 * annual report and the Q4 report can share the same period end (measured: NVIDIA 2026/FY and
 * 2026/Q4 both end on 01-24), so de-duplicating by period end alone would drop one of them. "Most
 * recent period" is always ordered by period-end date; the fiscal year can run ahead of the
 * calendar year and must not be used for ordering.
 */
@Repository
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class FinancialRepository {

  /** The most recent period of one statement type for one instrument, used to judge staleness. */
  public record Latest(long instrumentId, String statement, LocalDate periodEnd) {}

  private final JdbcTemplate jdbc;

  public FinancialRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Idempotently writes several periods of one instrument and returns the number of periods
   * written. Line items are replaced per period so that no orphan rows remain after the broker
   * changes its fields.
   *
   * <p><b>Must run in a transaction</b> (added in 3.1.2): replacing a period is "DELETE, then batch
   * INSERT". Without a transaction a failed batch insert leaves the period row in {@code
   * financial_report} with an empty {@code financial_item}: an empty shell of a period. The
   * fundamentals audit judges report freshness by {@code period_end} only, so the shell even makes
   * it believe "this period is already here", and nobody notices (found in the 2026-09-25
   * full-project review). The transaction covers all periods of the call, so one failure rolls back
   * the whole instrument.
   *
   * <p>The period row is upserted with {@code RETURNING id}; on conflict PostgreSQL still returns
   * the existing row's id because the {@code DO UPDATE} branch always touches the row.
   */
  @org.springframework.transaction.annotation.Transactional
  public int upsertAll(long instrumentId, List<FinancialReport> reports) {
    int n = 0;
    for (FinancialReport r : reports) {
      if (r.periodEnd() == null || r.periodText() == null) {
        // without a period end or label the period cannot be identified: skip rather than invent
        // one
        continue;
      }
      Long reportId =
          jdbc.query(
              """
                    INSERT INTO financial_report (instrument_id, statement, period_end, period_text, fiscal_year,
                            currency, accounting_standards, auditor_report)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (instrument_id, statement, period_end, period_text) DO UPDATE SET
                        fiscal_year = EXCLUDED.fiscal_year, currency = EXCLUDED.currency,
                        accounting_standards = EXCLUDED.accounting_standards,
                        auditor_report = EXCLUDED.auditor_report, fetched_at = now()
                    RETURNING id""",
              rs -> rs.next() ? rs.getLong(1) : null,
              instrumentId,
              r.statement().name(),
              Date.valueOf(r.periodEnd()),
              r.periodText(),
              r.fiscalYear() == 0 ? null : r.fiscalYear(),
              r.currency(),
              r.accountingStandards(),
              r.auditorReport());
      if (reportId == null) {
        continue;
      }
      jdbc.update("DELETE FROM financial_item WHERE report_id = ?", reportId);
      List<Object[]> args = new ArrayList<>(r.items().size());
      for (FinancialReport.Item i : r.items()) {
        args.add(new Object[] {reportId, i.fieldId(), i.name(), i.value(), i.yoy(), i.qoq()});
      }
      if (!args.isEmpty()) {
        jdbc.batchUpdate(
            "INSERT INTO financial_item (report_id, field_id, field_name, value, yoy, qoq) "
                + "VALUES (?, ?, ?, ?, ?, ?)",
            args);
      }
      n++;
    }
    return n;
  }

  /**
   * The most recent {@code limit} periods of one statement type, newest period end first, with
   * their line items. A fiscal year stored as NULL comes back as 0. Items are loaded in one second
   * query; the id list is inlined, which is safe because the ids are longs read from the first
   * query.
   */
  public List<FinancialReport> find(
      Instrument instrument, long instrumentId, FinancialStatement statement, int limit) {
    record Row(
        long id,
        LocalDate periodEnd,
        String periodText,
        int fiscalYear,
        String currency,
        String standards,
        String auditor) {}
    List<Row> rows =
        jdbc.query(
            """
                SELECT id, period_end, period_text, fiscal_year, currency, accounting_standards, auditor_report
                FROM financial_report WHERE instrument_id = ? AND statement = ?
                ORDER BY period_end DESC, period_text DESC LIMIT ?""",
            (rs, i) ->
                new Row(
                    rs.getLong("id"),
                    rs.getDate("period_end").toLocalDate(),
                    rs.getString("period_text"),
                    rs.getInt("fiscal_year"),
                    rs.getString("currency"),
                    rs.getString("accounting_standards"),
                    rs.getString("auditor_report")),
            instrumentId,
            statement.name(),
            limit);
    if (rows.isEmpty()) {
      return List.of();
    }
    Map<Long, List<FinancialReport.Item>> items = new HashMap<>();
    String ids = String.join(",", rows.stream().map(r -> Long.toString(r.id())).toList());
    jdbc.query(
        "SELECT report_id, field_id, field_name, value, yoy, qoq FROM financial_item "
            + "WHERE report_id IN ("
            + ids
            + ") ORDER BY field_id",
        rs -> {
          items
              .computeIfAbsent(rs.getLong("report_id"), k -> new ArrayList<>())
              .add(
                  new FinancialReport.Item(
                      rs.getLong("field_id"),
                      rs.getString("field_name"),
                      rs.getBigDecimal("value"),
                      rs.getBigDecimal("yoy"),
                      rs.getBigDecimal("qoq")));
        });
    List<FinancialReport> out = new ArrayList<>(rows.size());
    for (Row r : rows) {
      out.add(
          new FinancialReport(
              instrument,
              statement,
              r.periodEnd(),
              r.fiscalYear(),
              r.periodText(),
              r.currency(),
              r.standards(),
              r.auditor(),
              items.getOrDefault(r.id(), List.of())));
    }
    return out;
  }

  /** Latest period end per instrument and statement type, for the staleness check. */
  public List<Latest> latestPeriods() {
    return jdbc.query(
        """
                SELECT instrument_id, statement, max(period_end) AS period_end
                FROM financial_report GROUP BY instrument_id, statement""",
        (rs, i) ->
            new Latest(
                rs.getLong("instrument_id"),
                rs.getString("statement"),
                rs.getDate("period_end").toLocalDate()));
  }

  public long countReports() {
    Long n = jdbc.queryForObject("SELECT count(*) FROM financial_report", Long.class);
    return n == null ? 0 : n;
  }
}
