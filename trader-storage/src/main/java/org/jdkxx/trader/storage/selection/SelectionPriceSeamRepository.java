package org.jdkxx.trader.storage.selection;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 盈透与富途的接缝核对结果（§25.6）。 */
@Repository
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class SelectionPriceSeamRepository {

    public record SeamRow(long instrumentId, String symbol, long ibkrConId, LocalDate ibkrFrom, LocalDate ibkrTo,
                          LocalDate futuFrom, int overlapDays, int comparedDays, int withinDays, BigDecimal maxAbsDiff,
                          boolean ok, String detail, Instant checkedAt) {
    }

    public record Summary(long checked, long ok) {
    }

    private static final RowMapper<SeamRow> MAPPER = (rs, n) -> new SeamRow(rs.getLong("instrument_id"), rs.getString("symbol"),
            rs.getLong("ibkr_con_id"), date(rs.getDate("ibkr_from")), date(rs.getDate("ibkr_to")), date(rs.getDate("futu_from")),
            rs.getInt("overlap_days"), rs.getInt("compared_days"), rs.getInt("within_days"), rs.getBigDecimal("max_abs_diff"),
            rs.getBoolean("ok"), rs.getString("detail"), rs.getTimestamp("checked_at").toInstant());

    private final JdbcTemplate jdbc;

    public SelectionPriceSeamRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(long instrumentId, long ibkrConId, LocalDate ibkrFrom, LocalDate ibkrTo, LocalDate futuFrom,
                     int overlapDays, int comparedDays, int withinDays, BigDecimal maxAbsDiff, boolean ok, String detail) {
        jdbc.update("""
                INSERT INTO selection_price_seam (instrument_id, ibkr_con_id, ibkr_from, ibkr_to, futu_from, overlap_days,
                        compared_days, within_days, max_abs_diff, ok, detail)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (instrument_id) DO UPDATE SET ibkr_con_id = EXCLUDED.ibkr_con_id, ibkr_from = EXCLUDED.ibkr_from,
                    ibkr_to = EXCLUDED.ibkr_to, futu_from = EXCLUDED.futu_from, overlap_days = EXCLUDED.overlap_days,
                    compared_days = EXCLUDED.compared_days, within_days = EXCLUDED.within_days,
                    max_abs_diff = EXCLUDED.max_abs_diff, ok = EXCLUDED.ok, detail = EXCLUDED.detail, checked_at = now()""",
                instrumentId, ibkrConId, sql(ibkrFrom), sql(ibkrTo), sql(futuFrom), overlapDays, comparedDays, withinDays,
                maxAbsDiff, ok, detail);
    }

    public void delete(long instrumentId) {
        jdbc.update("DELETE FROM selection_price_seam WHERE instrument_id = ?", instrumentId);
    }

    public List<SeamRow> findAll(boolean failedOnly) {
        return jdbc.query("SELECT s.*, i.symbol FROM selection_price_seam s JOIN instrument i ON i.id = s.instrument_id"
                + (failedOnly ? " WHERE NOT s.ok" : "") + " ORDER BY s.ok, i.symbol", MAPPER);
    }

    public Summary summary() {
        return jdbc.queryForObject("SELECT count(*) AS n, count(*) FILTER (WHERE ok) AS k FROM selection_price_seam",
                (rs, n) -> new Summary(rs.getLong("n"), rs.getLong("k")));
    }

    private static LocalDate date(Date d) {
        return d == null ? null : d.toLocalDate();
    }

    private static Date sql(LocalDate d) {
        return d == null ? null : Date.valueOf(d);
    }
}
