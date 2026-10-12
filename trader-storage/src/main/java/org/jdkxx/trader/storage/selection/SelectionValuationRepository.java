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

    /**
     * 用当天估值快照续上估值序列（每日作业，§25.6）：PE、PB 直接取快照（与历史序列同口径），
     * PS 快照没有，按上一行 PS × 当天 PE ÷ 上一行 PE 顺延——两次财报之间 PE 与 PS 的分母都不变，比值就是股价比值，且不受拆股影响；
     * PE 变号或为零时改用 PB 的比值，都不行就留空。已有历史序列（HIST）的那天不覆盖。返回写入行数。
     */
    public int appendFromSnapshot(LocalDate date, java.util.Collection<Long> instrumentIds) {
        if (instrumentIds.isEmpty()) {
            return 0;
        }
        return jdbc.update("""
                INSERT INTO selection_valuation (instrument_id, trade_date, pe_ttm, pb, ps, source)
                SELECT s.instrument_id, s.trade_date, s.pe_ttm, s.pb,
                       CASE WHEN p.ps IS NULL THEN NULL
                            WHEN p.pe_ttm IS NOT NULL AND p.pe_ttm <> 0 AND s.pe_ttm IS NOT NULL AND sign(s.pe_ttm) = sign(p.pe_ttm)
                                THEN round(p.ps * s.pe_ttm / p.pe_ttm, 4)
                            WHEN p.pb IS NOT NULL AND p.pb <> 0 AND s.pb IS NOT NULL AND sign(s.pb) = sign(p.pb)
                                THEN round(p.ps * s.pb / p.pb, 4)
                       END,
                       'SNAPSHOT'
                FROM valuation_snapshot s
                LEFT JOIN LATERAL (SELECT v.pe_ttm, v.pb, v.ps FROM selection_valuation v
                                   WHERE v.instrument_id = s.instrument_id AND v.trade_date < s.trade_date
                                   ORDER BY v.trade_date DESC LIMIT 1) p ON true
                WHERE s.trade_date = ? AND s.instrument_id = ANY (?)
                ON CONFLICT (instrument_id, trade_date) DO NOTHING""",
                Date.valueOf(date), (Object) instrumentIds.toArray(Long[]::new));
    }

    /** 某天有估值的标的数（审计用）。 */
    public long countOn(LocalDate date) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM selection_valuation WHERE trade_date = ?", Long.class, Date.valueOf(date));
        return n == null ? 0 : n;
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
