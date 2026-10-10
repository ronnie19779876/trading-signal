package org.jdkxx.trader.storage.selection;

import org.jdkxx.trader.domain.selection.ValuationMetric;
import org.jdkxx.trader.domain.selection.ValuationPoint;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** 量化选股的逐日估值（§25.6）。三种口径来自三次请求，按列分别写入。 */
@Repository
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class SelectionValuationRepository {

    public record Coverage(long rows, long instruments, LocalDate earliest, LocalDate latest, long pe, long pb, long ps) {
    }

    /** 与估值快照同一天的核对：历史序列 PE 与快照 pe_ttm 已实测逐位一致，PB 待核（§25.6）。 */
    public record Agreement(long compared, long peMismatch, long pbMismatch, List<Mismatch> samples) {
    }

    public record Mismatch(String symbol, LocalDate tradeDate, BigDecimal histPe, BigDecimal snapPe, BigDecimal histPb,
                           BigDecimal snapPb) {
    }

    /** 两边都只保留到 3~4 位小数，差在这以内算一致。 */
    static final String TOLERANCE = "0.0015";

    private final JdbcTemplate jdbc;

    public SelectionValuationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 写入一种口径的富途估值历史，返回写入行数。已有行只覆盖这一列。 */
    public int upsertHistory(long instrumentId, ValuationMetric metric, List<ValuationPoint> points) {
        String column = column(metric);
        List<Object[]> args = new ArrayList<>(points.size());
        for (ValuationPoint p : points) {
            args.add(new Object[] {instrumentId, Date.valueOf(p.tradeDate()), p.value()});
        }
        if (args.isEmpty()) {
            return 0;
        }
        jdbc.batchUpdate("INSERT INTO selection_valuation (instrument_id, trade_date, " + column + ", source) VALUES (?, ?, ?, 'HIST') "
                + "ON CONFLICT (instrument_id, trade_date) DO UPDATE SET " + column + " = EXCLUDED." + column
                + ", source = 'HIST', fetched_at = now()", args);
        return args.size();
    }

    public Coverage coverage() {
        return jdbc.queryForObject("""
                SELECT count(*) AS n, count(DISTINCT instrument_id) AS k, min(trade_date) AS lo, max(trade_date) AS hi,
                       count(pe_ttm) AS pe, count(pb) AS pb, count(ps) AS ps
                FROM selection_valuation""",
                (rs, n) -> new Coverage(rs.getLong("n"), rs.getLong("k"),
                        rs.getDate("lo") == null ? null : rs.getDate("lo").toLocalDate(),
                        rs.getDate("hi") == null ? null : rs.getDate("hi").toLocalDate(),
                        rs.getLong("pe"), rs.getLong("pb"), rs.getLong("ps")));
    }

    public Agreement snapshotAgreement(int sampleLimit) {
        String join = """
                FROM selection_valuation v JOIN valuation_snapshot s ON s.instrument_id = v.instrument_id AND s.trade_date = v.trade_date
                WHERE v.source = 'HIST'""";
        String peBad = "v.pe_ttm IS NOT NULL AND s.pe_ttm IS NOT NULL AND abs(v.pe_ttm - s.pe_ttm) > " + TOLERANCE;
        String pbBad = "v.pb IS NOT NULL AND s.pb IS NOT NULL AND abs(v.pb - s.pb) > " + TOLERANCE;
        long[] counts = jdbc.queryForObject("SELECT count(*) AS n, count(*) FILTER (WHERE " + peBad + ") AS pe, "
                        + "count(*) FILTER (WHERE " + pbBad + ") AS pb " + join,
                (rs, n) -> new long[] {rs.getLong("n"), rs.getLong("pe"), rs.getLong("pb")});
        List<Mismatch> samples = jdbc.query("SELECT i.symbol, v.trade_date, v.pe_ttm AS hpe, s.pe_ttm AS spe, v.pb AS hpb, s.pb AS spb "
                        + join.replace("WHERE", "JOIN instrument i ON i.id = v.instrument_id WHERE")
                        + " AND ((" + peBad + ") OR (" + pbBad + ")) ORDER BY v.trade_date DESC, i.symbol LIMIT ?",
                (rs, n) -> new Mismatch(rs.getString("symbol"), rs.getDate("trade_date").toLocalDate(), rs.getBigDecimal("hpe"),
                        rs.getBigDecimal("spe"), rs.getBigDecimal("hpb"), rs.getBigDecimal("spb")),
                sampleLimit);
        return new Agreement(counts[0], counts[1], counts[2], samples);
    }

    static String column(ValuationMetric metric) {
        return switch (metric) {
            case PE -> "pe_ttm";
            case PB -> "pb";
            case PS -> "ps";
        };
    }
}
