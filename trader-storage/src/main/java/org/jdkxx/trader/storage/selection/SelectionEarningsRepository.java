package org.jdkxx.trader.storage.selection;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.jdkxx.trader.domain.selection.EarningsEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Earnings announcement dates (§25.6). Futu only returns the latest 20 entries, including future
 * scheduled dates, and <b>periods can be missing in between</b> (measured 2026-10-11: KO lacks
 * 2022/Q3~2023/Q1, BRK.B lacks more); missing periods fall back to the point-in-time rule. Writes
 * overwrite, so a changed scheduled date is followed.
 *
 * <p>{@code financial_report.period_end} is stored one day earlier than the real period end, hence
 * the {@code period_end + 1} in every date comparison below. Dates are those of the effective
 * trading day Futu reports.
 */
@Repository
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class SelectionEarningsRepository {

  /**
   * How well announcement dates match quarterly reports (only instruments whose announcement dates
   * were backfilled successfully), using the same rule as scoring: <b>nearest by date</b>, i.e. the
   * first announcement trading day within 75 days after the period end (before 3.3.0 matching was
   * by period label, inconsistent with the model, and retailers with January/February fiscal years
   * showed false "mismatches").
   *
   * @param reports number of quarterly periods (income statement) with period end not before {@code
   *     from}
   * @param matched of those, how many have an announcement within 75 days (the rest get a usable
   *     date from the fallback rule)
   * @param beyondRule matched, but announced after the statutory deadline (period end + 1, then +40
   *     days for Q1~Q3 or +60 days for Q4, rolled forward to the next trading day if that is not
   *     one). A fallback based on the rule alone would be too early, so the fallback takes
   *     max(rule, largest observed lag)
   * @param medianLagDays median number of days from period end to announcement
   */
  public record Matching(
      long reports,
      long matched,
      long beyondRule,
      Double medianLagDays,
      List<String> unmatchedSamples,
      List<String> beyondRuleSamples) {}

  private final JdbcTemplate jdbc;

  public SelectionEarningsRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Upserts one instrument's announcement dates keyed by period label; a fiscal year of 0 is stored
   * as NULL. Rows no longer returned by Futu are kept, since Futu only returns the latest 20.
   */
  public int upsertAll(long instrumentId, List<EarningsEvent> events) {
    List<Object[]> args = new ArrayList<>(events.size());
    for (EarningsEvent e : events) {
      args.add(
          new Object[] {
            instrumentId,
            e.periodText(),
            e.fiscalYear() == 0 ? null : e.fiscalYear(),
            Date.valueOf(e.pubDate()),
            e.pubTimeText(),
            e.timing().name(),
            e.current()
          });
    }
    if (args.isEmpty()) {
      return 0;
    }
    jdbc.batchUpdate(
        """
                INSERT INTO selection_earnings_event (instrument_id, period_text, fiscal_year, pub_date, pub_time_text, timing, is_current)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (instrument_id, period_text) DO UPDATE SET fiscal_year = EXCLUDED.fiscal_year,
                    pub_date = EXCLUDED.pub_date, pub_time_text = EXCLUDED.pub_time_text, timing = EXCLUDED.timing,
                    is_current = EXCLUDED.is_current, fetched_at = now()""",
        args);
    return args.size();
  }

  /**
   * Instruments with an announcement trading day in [from, to] (the daily job uses this to re-fetch
   * just-published reports and valuations).
   */
  public java.util.Set<Long> instrumentsWithPubBetween(LocalDate from, LocalDate to) {
    return new java.util.HashSet<>(
        jdbc.queryForList(
            "SELECT DISTINCT instrument_id FROM selection_earnings_event WHERE pub_date BETWEEN ? AND ?",
            Long.class,
            Date.valueOf(from),
            Date.valueOf(to)));
  }

  /**
   * All announcement dates of one instrument (including future scheduled dates), earliest first.
   * Instruments whose announcement dates were never backfilled (ETFs, outside the selection
   * universe) return an empty list. The entry-signal model input uses this to tell whether earnings
   * were just published or are about to be (3.4.8).
   */
  public List<EarningsEvent> events(long instrumentId) {
    return jdbc.query(
        """
                        SELECT period_text, fiscal_year, pub_date, pub_time_text, timing, is_current
                          FROM selection_earnings_event WHERE instrument_id = ? ORDER BY pub_date, period_text""",
        (rs, n) ->
            new EarningsEvent(
                rs.getString(1),
                rs.getInt(2),
                rs.getDate(3).toLocalDate(),
                rs.getString(4),
                org.jdkxx.trader.domain.selection.EarningsTiming.valueOf(rs.getString(5)),
                rs.getBoolean(6)),
        instrumentId);
  }

  /**
   * Which statement types (INCOME / BALANCE_SHEET / CASH_FLOW) are still missing for the quarter of
   * one announcement.
   */
  public record IncompleteFinancials(
      long instrumentId, String symbol, LocalDate pubDate, List<String> missing) {}

  /**
   * Announcements with a trading day in [from, to] whose quarter does <b>not yet have all three
   * statements</b> (income statement, balance sheet, cash flow statement) (3.4.6). "That quarter's
   * statement" uses the same nearest rule as {@link #matching}: period end before the announcement,
   * and the announcement within 75 days after the period end (quarterly rows, {@code /Q[1-4]}).
   *
   * <p>Background (measured 2026-10-22 on production and the dev instance): for a new quarter Futu
   * delivers <b>the income statement first and the other two later</b> (JPM, BAC, GS had the new
   * balance sheet only a week after the announcement and still no cash flow; AZO still lacked the
   * cash flow 29 days after). The daily job used to re-fetch only within 6 days of the
   * announcement, so late statements were never picked up.
   *
   * <p>Each announcement is crossed with the three statement types and kept where no matching
   * report exists; the rows are then folded into one entry per (instrument, announcement date)
   * listing the missing types.
   */
  public List<IncompleteFinancials> incompleteFinancials(LocalDate from, LocalDate to) {
    // Plain string concatenation with explicit leading spaces: a text block strips leading
    // whitespace from each line (that is how the 3.4.0 "instrument_idWHERE" bug happened).
    String sql =
        "SELECT e.instrument_id, i.symbol, e.pub_date, st.statement"
            + " FROM selection_earnings_event e"
            + " JOIN instrument i ON i.id = e.instrument_id"
            + " CROSS JOIN (VALUES ('INCOME'), ('BALANCE_SHEET'), ('CASH_FLOW')) AS st(statement)"
            + " WHERE e.pub_date BETWEEN ? AND ?"
            + "   AND NOT EXISTS (SELECT 1 FROM financial_report r"
            + "                   WHERE r.instrument_id = e.instrument_id AND r.statement = st.statement"
            + "                     AND r.period_text ~ '/Q[1-4]$'"
            + "                     AND e.pub_date > r.period_end + 1 AND e.pub_date <= r.period_end + 1 + 75)"
            + " ORDER BY i.symbol, e.pub_date, st.statement";
    java.util.Map<String, IncompleteFinancials> out = new java.util.LinkedHashMap<>();
    jdbc.query(
        sql,
        rs -> {
          long id = rs.getLong(1);
          LocalDate pub = rs.getDate(3).toLocalDate();
          out.computeIfAbsent(
                  id + "|" + pub,
                  k -> {
                    try {
                      return new IncompleteFinancials(id, rs.getString(2), pub, new ArrayList<>());
                    } catch (java.sql.SQLException e) {
                      throw new IllegalStateException(e);
                    }
                  })
              .missing()
              .add(rs.getString(4));
        },
        Date.valueOf(from),
        Date.valueOf(to));
    return out.values().stream()
        .map(
            x ->
                new IncompleteFinancials(
                    x.instrumentId(), x.symbol(), x.pubDate(), List.copyOf(x.missing())))
        .toList();
  }

  /**
   * Computes {@link Matching}. The shared CTE text block ends with a newline, so the statements
   * appended to it start on a fresh line. {@code pub - period_end} is a date difference in days.
   */
  public Matching matching(LocalDate from, int sampleLimit) {
    String base =
        """
                WITH base AS (
                    SELECT r.instrument_id, r.period_text, r.period_end, i.symbol,
                           (SELECT min(e.pub_date) FROM selection_earnings_event e WHERE e.instrument_id = r.instrument_id
                              AND e.pub_date > r.period_end + 1 AND e.pub_date <= r.period_end + 1 + 75) AS pub
                    FROM financial_report r JOIN instrument i ON i.id = r.instrument_id
                    WHERE r.statement = 'INCOME' AND r.period_text ~ '/Q[1-4]$' AND r.period_end >= ?
                      AND r.instrument_id IN (SELECT instrument_id FROM selection_backfill_state WHERE part = 'EARNINGS' AND status = 'OK'))
                """;
    // When the rule date falls on a weekend or holiday, use the first trading day after it: Futu
    // reports the "effective trading day", so an announcement made on a Saturday is recorded as the
    // following Monday.
    String beyond =
        "pub > (SELECT min(t.trade_date) FROM trading_day t WHERE t.market = 'US' AND t.trade_date >= "
            + "period_end + 1 + CASE WHEN period_text LIKE '%/Q4' THEN 60 ELSE 40 END)";
    Date f = Date.valueOf(from);
    Object[] head =
        jdbc.queryForObject(
            base
                + "SELECT count(*) AS n, count(pub) AS m, count(*) FILTER (WHERE "
                + beyond
                + ") AS late, "
                + "percentile_cont(0.5) WITHIN GROUP (ORDER BY pub - period_end) AS lag FROM base",
            (rs, n) ->
                new Object[] {
                  rs.getLong("n"),
                  rs.getLong("m"),
                  rs.getLong("late"),
                  rs.getObject("lag") == null ? null : rs.getDouble("lag")
                },
            f);
    List<String> unmatched =
        jdbc.query(
            base
                + "SELECT symbol || ' ' || period_text || '@' || period_end AS s FROM base "
                + "WHERE pub IS NULL ORDER BY period_end DESC, symbol LIMIT ?",
            (rs, n) -> rs.getString("s"),
            f,
            sampleLimit);
    List<String> late =
        jdbc.query(
            base
                + "SELECT symbol || ' ' || period_text || ' 期末 ' || period_end || ' 公告 ' || pub AS s FROM base "
                + "WHERE "
                + beyond
                + " ORDER BY period_end DESC, symbol LIMIT ?",
            (rs, n) -> rs.getString("s"),
            f,
            sampleLimit);
    return new Matching(
        (Long) head[0], (Long) head[1], (Long) head[2], (Double) head[3], unmatched, late);
  }
}
