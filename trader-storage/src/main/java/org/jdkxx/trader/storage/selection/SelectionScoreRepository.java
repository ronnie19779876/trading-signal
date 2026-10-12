package org.jdkxx.trader.storage.selection;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 量化选股评分（§25.7）。一天一批整体替换：同一天重算不会留下旧口径的行。 */
@Repository
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class SelectionScoreRepository {

    public record ScoreRow(String modelVersion, LocalDate tradeDate, long instrumentId, String sector, String exclusion,
                           String exclusionDetail, BigDecimal valueScore, BigDecimal growthScore, BigDecimal profitScore,
                           BigDecimal momentumScore, BigDecimal composite, BigDecimal compositePct, String rating, int sbStreak,
                           String metricsJson, String fingerprint) {
    }

    public record ScoreView(LocalDate tradeDate, long instrumentId, String symbol, String sector, String exclusion,
                            String exclusionDetail, BigDecimal valueScore, BigDecimal growthScore, BigDecimal profitScore,
                            BigDecimal momentumScore, BigDecimal composite, BigDecimal compositePct, String rating, int sbStreak,
                            String metrics, String fingerprint, Instant computedAt) {
    }

    /** 某天的计数：总行数、已评级、各评级、各排除原因、各板块已评级。 */
    public record DayStats(long rows, long rated, Map<String, Long> byRating, Map<String, Long> byExclusion, Map<String, Long> ratedBySector) {
    }

    private static final RowMapper<ScoreView> VIEW = (rs, n) -> new ScoreView(rs.getDate("trade_date").toLocalDate(),
            rs.getLong("instrument_id"), rs.getString("symbol"), rs.getString("sector"), rs.getString("exclusion"),
            rs.getString("exclusion_detail"), rs.getBigDecimal("value_score"), rs.getBigDecimal("growth_score"),
            rs.getBigDecimal("profit_score"), rs.getBigDecimal("momentum_score"), rs.getBigDecimal("composite"),
            rs.getBigDecimal("composite_pct"), rs.getString("rating"), rs.getInt("sb_streak"), rs.getString("metrics"),
            rs.getString("fingerprint"), rs.getTimestamp("computed_at").toInstant());

    private final JdbcTemplate jdbc;

    public SelectionScoreRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public int replaceDay(String modelVersion, LocalDate date, List<ScoreRow> rows) {
        jdbc.update("DELETE FROM selection_score WHERE model_version = ? AND trade_date = ?", modelVersion, Date.valueOf(date));
        List<Object[]> args = new ArrayList<>(rows.size());
        for (ScoreRow r : rows) {
            args.add(new Object[] {r.modelVersion(), Date.valueOf(r.tradeDate()), r.instrumentId(), r.sector(), r.exclusion(),
                    r.exclusionDetail(), r.valueScore(), r.growthScore(), r.profitScore(), r.momentumScore(), r.composite(),
                    r.compositePct(), r.rating(), r.sbStreak(), r.metricsJson(), r.fingerprint()});
        }
        if (!args.isEmpty()) {
            jdbc.batchUpdate("""
                    INSERT INTO selection_score (model_version, trade_date, instrument_id, sector, exclusion, exclusion_detail,
                            value_score, growth_score, profit_score, momentum_score, composite, composite_pct, rating, sb_streak,
                            metrics, fingerprint)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)""", args);
        }
        return args.size();
    }

    /** 某天各标的的连续 STRONG_BUY 天数（算下一天的 streak 用）。 */
    public Map<Long, Integer> streaks(String modelVersion, LocalDate date) {
        Map<Long, Integer> out = new HashMap<>();
        jdbc.query("SELECT instrument_id, sb_streak FROM selection_score WHERE model_version = ? AND trade_date = ?",
                rs -> {
                    out.put(rs.getLong(1), rs.getInt(2));
                }, modelVersion, Date.valueOf(date));
        return out;
    }

    /** 早于 date 的最近一个评分日。 */
    public Optional<LocalDate> latestBefore(String modelVersion, LocalDate date) {
        return jdbc.query("SELECT max(trade_date) FROM selection_score WHERE model_version = ? AND trade_date < ? HAVING count(*) > 0",
                (rs, n) -> rs.getDate(1).toLocalDate(), modelVersion, Date.valueOf(date)).stream().findFirst();
    }

    public Optional<LocalDate> latestDate(String modelVersion) {
        return jdbc.query("SELECT max(trade_date) FROM selection_score WHERE model_version = ? HAVING count(*) > 0",
                (rs, n) -> rs.getDate(1).toLocalDate(), modelVersion).stream().findFirst();
    }

    /** 某天全表；sector / rating 为空不过滤。按合成分从高到低，未评级的排后面。 */
    public List<ScoreView> day(String modelVersion, LocalDate date, String sector, String rating) {
        StringBuilder sql = new StringBuilder("""
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

    public List<ScoreView> history(String modelVersion, long instrumentId, LocalDate from, LocalDate to) {
        return jdbc.query("""
                SELECT s.*, i.symbol FROM selection_score s JOIN instrument i ON i.id = s.instrument_id
                WHERE s.model_version = ? AND s.instrument_id = ? AND s.trade_date BETWEEN ? AND ? ORDER BY s.trade_date""",
                VIEW, modelVersion, instrumentId, Date.valueOf(from), Date.valueOf(to));
    }

    public DayStats dayStats(String modelVersion, LocalDate date) {
        Date d = Date.valueOf(date);
        long[] head = jdbc.queryForObject("SELECT count(*) AS n, count(rating) AS r FROM selection_score WHERE model_version = ? AND trade_date = ?",
                (rs, n) -> new long[] {rs.getLong("n"), rs.getLong("r")}, modelVersion, d);
        return new DayStats(head[0], head[1],
                group("rating", "rating IS NOT NULL", modelVersion, d),
                group("exclusion", "exclusion IS NOT NULL", modelVersion, d),
                group("sector", "rating IS NOT NULL", modelVersion, d));
    }

    /** 最近的评分日，从新到旧（前端日期选择与「上一个评分日」用）。 */
    public List<LocalDate> days(String modelVersion, int limit) {
        return jdbc.query("SELECT DISTINCT trade_date FROM selection_score WHERE model_version = ? ORDER BY trade_date DESC LIMIT ?",
                (rs, n) -> rs.getDate(1).toLocalDate(), modelVersion, limit);
    }

    /** 评分覆盖的日期范围与天数（重放进度用）。 */
    public record Span(LocalDate first, LocalDate last, long days) {
    }

    public Optional<Span> span(String modelVersion) {
        return jdbc.query("""
                SELECT min(trade_date) AS lo, max(trade_date) AS hi, count(DISTINCT trade_date) AS n
                FROM selection_score WHERE model_version = ? HAVING count(*) > 0""",
                (rs, n) -> new Span(rs.getDate("lo").toLocalDate(), rs.getDate("hi").toLocalDate(), rs.getLong("n")),
                modelVersion).stream().findFirst();
    }

    private Map<String, Long> group(String column, String where, String modelVersion, Date d) {
        Map<String, Long> out = new java.util.LinkedHashMap<>();
        jdbc.query("SELECT " + column + " AS k, count(*) AS n FROM selection_score WHERE model_version = ? AND trade_date = ? AND "
                        + where + " GROUP BY 1 ORDER BY 2 DESC, 1",
                rs -> {
                    out.put(rs.getString("k"), rs.getLong("n"));
                }, modelVersion, d);
        return out;
    }
}
