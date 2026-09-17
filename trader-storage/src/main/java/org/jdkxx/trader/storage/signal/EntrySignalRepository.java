package org.jdkxx.trader.storage.signal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class EntrySignalRepository {

    private static final String SELECT = "SELECT s.*, i.symbol FROM entry_signal s JOIN instrument i ON i.id = s.instrument_id";

    private static final RowMapper<EntrySignalRow> MAPPER = (rs, n) -> new EntrySignalRow(
            rs.getLong("id"), rs.getLong("instrument_id"), rs.getString("symbol"), rs.getDate("trade_date").toLocalDate(),
            rs.getString("ruleset_version"), rs.getString("role"), rs.getString("origin"), rs.getBigDecimal("close"),
            rs.getBigDecimal("atr14"), rs.getBigDecimal("stop"), rs.getString("stop_leg"), rs.getBigDecimal("stop_distance"),
            rs.getBigDecimal("risk_per_share"), rs.getBigDecimal("plus_one_r"), rs.getBigDecimal("chandelier_stop"),
            rs.getBigDecimal("target"), rs.getBigDecimal("reward_risk"), rs.getBigDecimal("zone_bottom"),
            rs.getBigDecimal("zone_top"), rs.getObject("zone_touches") == null ? null : rs.getInt("zone_touches"),
            rs.getString("bonus"), rs.getObject("ai_analysis_id") == null ? null : rs.getLong("ai_analysis_id"),
            rs.getString("ai_stance"), rs.getString("status"), rs.getDate("expires_on").toLocalDate(), rs.getString("note"),
            rs.getTimestamp("status_changed_at") == null ? null : rs.getTimestamp("status_changed_at").toInstant(),
            rs.getObject("job_run_id") == null ? null : rs.getLong("job_run_id"), rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public EntrySignalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 写入一条信号；同一标的同一天同一版本已有时不动（重跑不重复发信号），返回已有或新建的 id 与是否新建。
     * id / symbol / status 以外的审计字段忽略。
     */
    public Inserted insertIfAbsent(EntrySignalRow r) {
        List<Long> ids = jdbc.queryForList("""
                INSERT INTO entry_signal (instrument_id, trade_date, ruleset_version, role, origin, close, atr14, stop, stop_leg,
                        stop_distance, risk_per_share, plus_one_r, chandelier_stop, target, reward_risk, zone_bottom, zone_top,
                        zone_touches, bonus, status, expires_on, job_run_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                ON CONFLICT (instrument_id, trade_date, ruleset_version) DO NOTHING
                RETURNING id""", Long.class,
                r.instrumentId(), Date.valueOf(r.tradeDate()), r.rulesetVersion(), r.role(), r.origin(), r.close(), r.atr14(),
                r.stop(), r.stopLeg(), r.stopDistance(), r.riskPerShare(), r.plusOneR(), r.chandelierStop(), r.target(),
                r.rewardRisk(), r.zoneBottom(), r.zoneTop(), r.zoneTouches(), r.bonus(), r.status(),
                Date.valueOf(r.expiresOn()), r.jobRunId());
        if (!ids.isEmpty()) {
            return new Inserted(ids.get(0), true);
        }
        Long existing = jdbc.queryForObject("SELECT id FROM entry_signal WHERE instrument_id = ? AND trade_date = ? AND ruleset_version = ?",
                Long.class, r.instrumentId(), Date.valueOf(r.tradeDate()), r.rulesetVersion());
        return new Inserted(existing, false);
    }

    public record Inserted(long id, boolean created) {
    }

    public Optional<EntrySignalRow> find(long id) {
        return jdbc.query(SELECT + " WHERE s.id = ?", MAPPER, id).stream().findFirst();
    }

    /**
     * 信号列表，按判定日倒序。
     *
     * @param statuses 为空不过滤
     * @param poolOnly 只要池与持仓
     */
    public List<EntrySignalRow> list(LocalDate from, LocalDate to, Collection<String> statuses, boolean poolOnly, String origin) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE s.trade_date BETWEEN ? AND ?");
        List<Object> args = new ArrayList<>(List.of(Date.valueOf(from), Date.valueOf(to)));
        if (statuses != null && !statuses.isEmpty()) {
            sql.append(" AND s.status = ANY (?)");
            args.add(statuses.toArray(String[]::new));
        }
        if (poolOnly) {
            sql.append(" AND s.role IN ('POOL', 'HOLDING')");
        }
        if (origin != null) {
            sql.append(" AND s.origin = ?");
            args.add(origin);
        }
        sql.append(" ORDER BY s.trade_date DESC, i.symbol");
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    public List<EntrySignalRow> on(LocalDate date, String version) {
        return jdbc.query(SELECT + " WHERE s.trade_date = ? AND s.ruleset_version = ? ORDER BY i.symbol", MAPPER,
                Date.valueOf(date), version);
    }

    /** 每只标的判定日之前最近一条<b>未被否决</b>的信号日期（冷却期用）。 */
    public Map<Long, LocalDate> lastSignalDatesBefore(LocalDate date, String version) {
        Map<Long, LocalDate> m = new HashMap<>();
        jdbc.query("""
                SELECT instrument_id, max(trade_date) AS d FROM entry_signal
                WHERE trade_date < ? AND ruleset_version = ? AND status <> 'VETOED'
                GROUP BY instrument_id""",
                rs -> { m.put(rs.getLong("instrument_id"), rs.getDate("d").toLocalDate()); },
                Date.valueOf(date), version);
        return m;
    }

    /** 把有效期截止日不晚于 date 的 NEW / ACKNOWLEDGED 标为过期，返回条数。 */
    public int expire(LocalDate date) {
        return jdbc.update("""
                UPDATE entry_signal SET status = 'EXPIRED', status_changed_at = now()
                WHERE status IN ('NEW', 'ACKNOWLEDGED') AND expires_on <= ?""", Date.valueOf(date));
    }

    /** 状态迁移：只有当前状态在 from 里才改，返回是否改了。 */
    public boolean transition(long id, Collection<String> from, String to, String note) {
        return jdbc.update("""
                UPDATE entry_signal SET status = ?, note = COALESCE(?, note), status_changed_at = now()
                WHERE id = ? AND status = ANY (?)""", to, note, id, from.toArray(String[]::new)) == 1;
    }
}
