package org.jdkxx.trader.storage.selection;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quant stock-selection scores (§25.7). Each day is replaced as one batch, so recomputing a day
 * leaves no rows from an older definition behind.
 *
 * <p>Single-value aggregates ({@code max}, {@code min}) use {@code HAVING count(*) > 0} so that "no
 * rows" comes back as no result row instead of a row of NULLs that the mapper would trip over.
 */
@Repository
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class SelectionScoreRepository {

  public record ScoreRow(
      String modelVersion,
      LocalDate tradeDate,
      long instrumentId,
      String sector,
      String exclusion,
      String exclusionDetail,
      BigDecimal valueScore,
      BigDecimal growthScore,
      BigDecimal profitScore,
      BigDecimal momentumScore,
      BigDecimal composite,
      BigDecimal compositePct,
      String rating,
      int sbStreak,
      String metricsJson,
      String fingerprint) {}

  public record ScoreView(
      LocalDate tradeDate,
      long instrumentId,
      String symbol,
      String sector,
      String exclusion,
      String exclusionDetail,
      BigDecimal valueScore,
      BigDecimal growthScore,
      BigDecimal profitScore,
      BigDecimal momentumScore,
      BigDecimal composite,
      BigDecimal compositePct,
      String rating,
      int sbStreak,
      String metrics,
      String fingerprint,
      Instant computedAt) {}

  /** Counts for one day: total rows, rated, per rating, per exclusion reason, rated per sector. */
  public record DayStats(
      long rows,
      long rated,
      Map<String, Long> byRating,
      Map<String, Long> byExclusion,
      Map<String, Long> ratedBySector) {}

  private static final RowMapper<ScoreView> VIEW =
      (rs, n) ->
          new ScoreView(
              rs.getDate("trade_date").toLocalDate(),
              rs.getLong("instrument_id"),
              rs.getString("symbol"),
              rs.getString("sector"),
              rs.getString("exclusion"),
              rs.getString("exclusion_detail"),
              rs.getBigDecimal("value_score"),
              rs.getBigDecimal("growth_score"),
              rs.getBigDecimal("profit_score"),
              rs.getBigDecimal("momentum_score"),
              rs.getBigDecimal("composite"),
              rs.getBigDecimal("composite_pct"),
              rs.getString("rating"),
              rs.getInt("sb_streak"),
              rs.getString("metrics"),
              rs.getString("fingerprint"),
              rs.getTimestamp("computed_at").toInstant());

  private final JdbcTemplate jdbc;

  public SelectionScoreRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Replaces all scores of one model version and day (delete-then-insert in one transaction, so
   * readers never see a half-written day). The rows are expected to carry the same model version
   * and date as the arguments; the delete uses the arguments, the insert uses the rows' own values.
   */
  @Transactional
  public int replaceDay(String modelVersion, LocalDate date, List<ScoreRow> rows) {
    jdbc.update(
        "DELETE FROM selection_score WHERE model_version = ? AND trade_date = ?",
        modelVersion,
        Date.valueOf(date));
    List<Object[]> args = new ArrayList<>(rows.size());
    for (ScoreRow r : rows) {
      args.add(
          new Object[] {
            r.modelVersion(),
            Date.valueOf(r.tradeDate()),
            r.instrumentId(),
            r.sector(),
            r.exclusion(),
            r.exclusionDetail(),
            r.valueScore(),
            r.growthScore(),
            r.profitScore(),
            r.momentumScore(),
            r.composite(),
            r.compositePct(),
            r.rating(),
            r.sbStreak(),
            r.metricsJson(),
            r.fingerprint()
          });
    }
    if (!args.isEmpty()) {
      jdbc.batchUpdate(
          """
                    INSERT INTO selection_score (model_version, trade_date, instrument_id, sector, exclusion, exclusion_detail,
                            value_score, growth_score, profit_score, momentum_score, composite, composite_pct, rating, sb_streak,
                            metrics, fingerprint)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)""",
          args);
    }
    return args.size();
  }

  /**
   * Consecutive STRONG_BUY days per instrument on a day (used to compute the next day's streak).
   */
  public Map<Long, Integer> streaks(String modelVersion, LocalDate date) {
    Map<Long, Integer> out = new HashMap<>();
    jdbc.query(
        "SELECT instrument_id, sb_streak FROM selection_score WHERE model_version = ? AND trade_date = ?",
        rs -> {
          out.put(rs.getLong(1), rs.getInt(2));
        },
        modelVersion,
        Date.valueOf(date));
    return out;
  }

  /** The latest score day before {@code date}. */
  public Optional<LocalDate> latestBefore(String modelVersion, LocalDate date) {
    return jdbc
        .query(
            "SELECT max(trade_date) FROM selection_score WHERE model_version = ? AND trade_date < ? HAVING count(*) > 0",
            (rs, n) -> rs.getDate(1).toLocalDate(),
            modelVersion,
            Date.valueOf(date))
        .stream()
        .findFirst();
  }

  public Optional<LocalDate> latestDate(String modelVersion) {
    return jdbc
        .query(
            "SELECT max(trade_date) FROM selection_score WHERE model_version = ? HAVING count(*) > 0",
            (rs, n) -> rs.getDate(1).toLocalDate(),
            modelVersion)
        .stream()
        .findFirst();
  }

  /**
   * The full table of one day; blank sector / rating means no filter. Ordered by composite score
   * descending, unscored last.
   */
  public List<ScoreView> day(String modelVersion, LocalDate date, String sector, String rating) {
    StringBuilder sql =
        new StringBuilder(
            """
                SELECT s.*, i.symbol FROM selection_score s JOIN instrument i ON i.id = s.instrument_id
                WHERE s.model_version = ? AND s.trade_date = ?""");
    List<Object> args = new ArrayList<>(List.of(modelVersion, Date.valueOf(date)));
    if (sector != null && !sector.isBlank()) {
      sql.append(" AND s.sector = ?");
      args.add(sector);
    }
    if (rating != null && !rating.isBlank()) {
      sql.append(" AND s.rating = ?");
      args.add(rating);
    }
    sql.append(" ORDER BY s.composite DESC NULLS LAST, i.symbol");
    return jdbc.query(sql.toString(), VIEW, args.toArray());
  }

  public List<ScoreView> history(
      String modelVersion, long instrumentId, LocalDate from, LocalDate to) {
    return jdbc.query(
        """
                SELECT s.*, i.symbol FROM selection_score s JOIN instrument i ON i.id = s.instrument_id
                WHERE s.model_version = ? AND s.instrument_id = ? AND s.trade_date BETWEEN ? AND ? ORDER BY s.trade_date""",
        VIEW,
        modelVersion,
        instrumentId,
        Date.valueOf(from),
        Date.valueOf(to));
  }

  public DayStats dayStats(String modelVersion, LocalDate date) {
    Date d = Date.valueOf(date);
    long[] head =
        jdbc.queryForObject(
            "SELECT count(*) AS n, count(rating) AS r FROM selection_score WHERE model_version = ? AND trade_date = ?",
            (rs, n) -> new long[] {rs.getLong("n"), rs.getLong("r")},
            modelVersion,
            d);
    return new DayStats(
        head[0],
        head[1],
        group("rating", "rating IS NOT NULL", modelVersion, d),
        group("exclusion", "exclusion IS NOT NULL", modelVersion, d),
        group("sector", "rating IS NOT NULL", modelVersion, d));
  }

  /** Recent score days, newest first (for the frontend date picker and "previous score day"). */
  public List<LocalDate> days(String modelVersion, int limit) {
    return jdbc.query(
        "SELECT DISTINCT trade_date FROM selection_score WHERE model_version = ? ORDER BY trade_date DESC LIMIT ?",
        (rs, n) -> rs.getDate(1).toLocalDate(),
        modelVersion,
        limit);
  }

  /** Date range and number of days covered by scores (for replay progress). */
  public record Span(LocalDate first, LocalDate last, long days) {}

  public Optional<Span> span(String modelVersion) {
    return jdbc
        .query(
            """
                SELECT min(trade_date) AS lo, max(trade_date) AS hi, count(DISTINCT trade_date) AS n
                FROM selection_score WHERE model_version = ? HAVING count(*) > 0""",
            (rs, n) ->
                new Span(
                    rs.getDate("lo").toLocalDate(),
                    rs.getDate("hi").toLocalDate(),
                    rs.getLong("n")),
            modelVersion)
        .stream()
        .findFirst();
  }

  /**
   * Counts per value of one column. {@code column} and {@code where} are concatenated into the SQL,
   * so they must only ever be the constant fragments passed from {@link #dayStats}, never caller
   * input.
   */
  private Map<String, Long> group(String column, String where, String modelVersion, Date d) {
    Map<String, Long> out = new java.util.LinkedHashMap<>();
    jdbc.query(
        "SELECT "
            + column
            + " AS k, count(*) AS n FROM selection_score WHERE model_version = ? AND trade_date = ? AND "
            + where
            + " GROUP BY 1 ORDER BY 2 DESC, 1",
        rs -> {
          out.put(rs.getString("k"), rs.getLong("n"));
        },
        modelVersion,
        d);
    return out;
  }
}
