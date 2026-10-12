package org.jdkxx.trader.storage.selection;

import org.jdkxx.trader.domain.selection.SplitAdjustedBar;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 量化选股打分的批量只读取数（§25.5）。重放一次性加载、按天切片，避免逐日逐只往返（开发实例经隧道每次往返约 330 ms）。
 */
@Repository
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class SelectionInputRepository {

    /** 打分要用的财报字段（三套模板，2026-10-12 开发库实测；编号按报表区分，不跨报表复用）。 */
    public static final long[] INCOME_FIELDS = {8002, 8004, 8043, 8048, 9002, 9046, 9051, 10002, 10047, 10052};
    public static final long[] BALANCE_FIELDS = {8085, 9070, 10065};
    public static final long[] CASH_FLOW_FIELDS = {8072, 9078, 10078};

    /** 一条季报数据项。periodEnd 是库内期末（比真实期末早一天）。 */
    public record FactRow(long instrumentId, String statement, LocalDate periodEnd, String periodText, long fieldId, double value) {
    }

    public record ValuationRow(long instrumentId, LocalDate tradeDate, Double pe, Double pb, Double ps) {
    }

    private final JdbcTemplate jdbc;

    public SelectionInputRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 季报（只要期别带 /Q 的单季）里打分要用的字段。 */
    public List<FactRow> quarterlyFacts(Collection<Long> instrumentIds) {
        List<FactRow> out = new ArrayList<>();
        if (instrumentIds.isEmpty()) {
            return out;
        }
        jdbc.query("""
                SELECT r.instrument_id, r.statement, r.period_end, r.period_text, i.field_id, i.value
                FROM financial_report r JOIN financial_item i ON i.report_id = r.id
                WHERE r.instrument_id = ANY (?) AND r.period_text ~ '/Q[1-4]$' AND i.value IS NOT NULL
                  AND ((r.statement = 'INCOME' AND i.field_id = ANY (?)) OR (r.statement = 'BALANCE_SHEET' AND i.field_id = ANY (?))
                       OR (r.statement = 'CASH_FLOW' AND i.field_id = ANY (?)))
                ORDER BY r.instrument_id, r.period_end""",
                rs -> {
                    out.add(new FactRow(rs.getLong(1), rs.getString(2), rs.getDate(3).toLocalDate(), rs.getString(4),
                            rs.getLong(5), rs.getDouble(6)));
                },
                (Object) instrumentIds.toArray(Long[]::new), (Object) box(INCOME_FIELDS), (Object) box(BALANCE_FIELDS),
                (Object) box(CASH_FLOW_FIELDS));
        return out;
    }

    /** 标的 → 全部公告交易日（不分期别，按日期就近匹配用）。 */
    public Map<Long, List<LocalDate>> pubDates(Collection<Long> instrumentIds) {
        Map<Long, List<LocalDate>> out = new HashMap<>();
        if (instrumentIds.isEmpty()) {
            return out;
        }
        jdbc.query("SELECT instrument_id, pub_date FROM selection_earnings_event WHERE instrument_id = ANY (?) ORDER BY instrument_id, pub_date",
                rs -> {
                    out.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(rs.getDate(2).toLocalDate());
                }, (Object) instrumentIds.toArray(Long[]::new));
        return out;
    }

    /** 逐行回调估值（全量重放约 118 万行，不攒成大列表）。 */
    public void forEachValuation(Collection<Long> instrumentIds, LocalDate from, LocalDate to, Consumer<ValuationRow> consumer) {
        if (instrumentIds.isEmpty()) {
            return;
        }
        jdbc.query("""
                SELECT instrument_id, trade_date, pe_ttm, pb, ps FROM selection_valuation
                WHERE instrument_id = ANY (?) AND trade_date BETWEEN ? AND ? ORDER BY instrument_id, trade_date""",
                rs -> {
                    consumer.accept(new ValuationRow(rs.getLong(1), rs.getDate(2).toLocalDate(), num(rs, 3), num(rs, 4), num(rs, 5)));
                }, (Object) instrumentIds.toArray(Long[]::new), Date.valueOf(from), Date.valueOf(to));
    }

    /** 接缝核对通过的标的的盈透日线；没通过的不给（那一段动能记缺，§25.6）。 */
    public Map<Long, List<SplitAdjustedBar>> seamPassedIbkrBars(Collection<Long> instrumentIds) {
        Map<Long, List<SplitAdjustedBar>> out = new HashMap<>();
        if (instrumentIds.isEmpty()) {
            return out;
        }
        jdbc.query("""
                SELECT p.instrument_id, p.trade_date, p.close FROM selection_price_bar p
                JOIN selection_price_seam s ON s.instrument_id = p.instrument_id AND s.ok
                WHERE p.instrument_id = ANY (?) ORDER BY p.instrument_id, p.trade_date""",
                rs -> {
                    out.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(
                            new SplitAdjustedBar(rs.getDate(2).toLocalDate(), null, null, null, rs.getBigDecimal(3), 0));
                }, (Object) instrumentIds.toArray(Long[]::new));
        return out;
    }

    private static Double num(java.sql.ResultSet rs, int col) throws java.sql.SQLException {
        java.math.BigDecimal v = rs.getBigDecimal(col);
        return v == null ? null : v.doubleValue();
    }

    private static Long[] box(long[] a) {
        Long[] b = new Long[a.length];
        for (int i = 0; i < a.length; i++) {
            b[i] = a[i];
        }
        return b;
    }
}
