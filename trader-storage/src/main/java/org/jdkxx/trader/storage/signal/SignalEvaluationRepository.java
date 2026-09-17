package org.jdkxx.trader.storage.signal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class SignalEvaluationRepository {

    private static final String SELECT = """
            SELECT e.*, i.symbol FROM signal_evaluation e JOIN instrument i ON i.id = e.instrument_id
            """;

    private static final RowMapper<SignalEvaluationRow> MAPPER = (rs, n) -> new SignalEvaluationRow(
            rs.getLong("instrument_id"), rs.getString("symbol"), rs.getDate("trade_date").toLocalDate(),
            rs.getString("ruleset_version"), rs.getString("status"), rs.getString("status_detail"), rs.getString("outcome"),
            rs.getString("gates"), rs.getInt("gates_passed"), rs.getString("first_blocking_gate"), rs.getString("role"),
            rs.getBigDecimal("close"), rs.getBigDecimal("atr14"), rs.getBigDecimal("rvol"), rs.getBigDecimal("zone_bottom"),
            rs.getBigDecimal("stop"), rs.getBigDecimal("stop_distance"), rs.getString("input_fingerprint"),
            rs.getString("detail"), rs.getObject("job_run_id") == null ? null : rs.getLong("job_run_id"),
            rs.getTimestamp("evaluated_at").toInstant());

    private final JdbcTemplate jdbc;

    public SignalEvaluationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 同一标的同一天同一版本覆盖。symbol 与 evaluatedAt 忽略。 */
    public void upsert(SignalEvaluationRow r) {
        upsertAll(List.of(r));
    }

    public void upsertAll(List<SignalEvaluationRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                INSERT INTO signal_evaluation (instrument_id, trade_date, ruleset_version, status, status_detail, outcome, gates,
                        gates_passed, first_blocking_gate, role, close, atr14, rvol, zone_bottom, stop, stop_distance,
                        input_fingerprint, detail, job_run_id, evaluated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, now())
                ON CONFLICT (instrument_id, trade_date, ruleset_version) DO UPDATE SET
                    status = EXCLUDED.status, status_detail = EXCLUDED.status_detail, outcome = EXCLUDED.outcome,
                    gates = EXCLUDED.gates, gates_passed = EXCLUDED.gates_passed, first_blocking_gate = EXCLUDED.first_blocking_gate,
                    role = EXCLUDED.role, close = EXCLUDED.close, atr14 = EXCLUDED.atr14, rvol = EXCLUDED.rvol,
                    zone_bottom = EXCLUDED.zone_bottom, stop = EXCLUDED.stop, stop_distance = EXCLUDED.stop_distance,
                    input_fingerprint = EXCLUDED.input_fingerprint, detail = EXCLUDED.detail, job_run_id = EXCLUDED.job_run_id,
                    evaluated_at = now()""",
                rows.stream().map(r -> new Object[] {r.instrumentId(), Date.valueOf(r.tradeDate()), r.rulesetVersion(), r.status(),
                        r.statusDetail(), r.outcome(), r.gates(), r.gatesPassed(), r.firstBlockingGate(), r.role(), r.close(),
                        r.atr14(), r.rvol(), r.zoneBottom(), r.stop(), r.stopDistance(), r.inputFingerprint(), r.detail(),
                        r.jobRunId()}).toList());
    }

    public Optional<SignalEvaluationRow> find(long instrumentId, LocalDate date, String version) {
        return jdbc.query(SELECT + " WHERE e.instrument_id = ? AND e.trade_date = ? AND e.ruleset_version = ?", MAPPER,
                instrumentId, Date.valueOf(date), version).stream().findFirst();
    }

    /** 某天某版本的全部评估，按代码排序。 */
    public List<SignalEvaluationRow> on(LocalDate date, String version) {
        return jdbc.query(SELECT + " WHERE e.trade_date = ? AND e.ruleset_version = ? ORDER BY i.symbol", MAPPER,
                Date.valueOf(date), version);
    }

    /** 某天某版本的评估，按标的 id 索引（判定边沿用：取上一交易日的结论）。 */
    public Map<Long, SignalEvaluationRow> byInstrumentOn(LocalDate date, String version) {
        Map<Long, SignalEvaluationRow> m = new HashMap<>();
        on(date, version).forEach(r -> m.put(r.instrumentId(), r));
        return m;
    }

    public List<SignalEvaluationRow> history(long instrumentId, LocalDate from, LocalDate to, String version) {
        return jdbc.query(SELECT + " WHERE e.instrument_id = ? AND e.trade_date BETWEEN ? AND ? AND e.ruleset_version = ?"
                + " ORDER BY e.trade_date DESC", MAPPER, instrumentId, Date.valueOf(from), Date.valueOf(to), version);
    }

    /** 当天判为数据过期、但现在库里已经有当天 K 线的标的数（补偿检查用）。 */
    public long staleButNowHasBar(LocalDate date, String version) {
        Long n = jdbc.queryForObject("""
                SELECT count(*) FROM signal_evaluation e
                WHERE e.trade_date = ? AND e.ruleset_version = ? AND e.status = 'SKIPPED_STALE_DATA'
                  AND EXISTS (SELECT 1 FROM daily_bar b WHERE b.instrument_id = e.instrument_id AND b.trade_date = e.trade_date)""",
                Long.class, Date.valueOf(date), version);
        return n == null ? 0 : n;
    }
}
