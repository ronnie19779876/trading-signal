package org.jdkxx.trader.storage.signal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Repository
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class SignalTrackRepository {

    private static final RowMapper<SignalTrackRow> MAPPER = (rs, n) -> new SignalTrackRow(
            rs.getLong("signal_id"), rs.getString("variant"), rs.getString("status"), rs.getBigDecimal("stop"),
            rs.getBigDecimal("plus_one_r"), date(rs.getDate("entry_date")), rs.getBigDecimal("entry_price"),
            rs.getBoolean("touched_plus_one_r"), date(rs.getDate("exit_date")), rs.getBigDecimal("exit_price"),
            rs.getString("exit_reason"), rs.getBigDecimal("r_multiple"), rs.getBigDecimal("return_pct"),
            rs.getBigDecimal("mfe_r"), rs.getBigDecimal("mae_r"), rs.getObject("bars_held") == null ? null : rs.getInt("bars_held"),
            date(rs.getDate("updated_through")), rs.getTimestamp("updated_at").toInstant());

    private final JdbcTemplate jdbc;

    public SignalTrackRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static LocalDate date(Date d) {
        return d == null ? null : d.toLocalDate();
    }

    /** 新信号的账本行：已有则不动。 */
    public void createIfAbsent(long signalId, String variant, java.math.BigDecimal stop, java.math.BigDecimal plusOneR) {
        jdbc.update("""
                INSERT INTO signal_track (signal_id, variant, status, stop, plus_one_r) VALUES (?, ?, 'PENDING_ENTRY', ?, ?)
                ON CONFLICT (signal_id, variant) DO NOTHING""", signalId, variant, stop, plusOneR);
    }

    public void update(SignalTrackRow r) {
        jdbc.update("""
                UPDATE signal_track SET status = ?, entry_date = ?, entry_price = ?, touched_plus_one_r = ?, exit_date = ?,
                       exit_price = ?, exit_reason = ?, r_multiple = ?, return_pct = ?, mfe_r = ?, mae_r = ?, bars_held = ?,
                       updated_through = ?, updated_at = now()
                WHERE signal_id = ? AND variant = ?""",
                r.status(), r.entryDate() == null ? null : Date.valueOf(r.entryDate()), r.entryPrice(), r.touchedPlusOneR(),
                r.exitDate() == null ? null : Date.valueOf(r.exitDate()), r.exitPrice(), r.exitReason(), r.rMultiple(),
                r.returnPct(), r.mfeR(), r.maeR(), r.barsHeld(),
                r.updatedThrough() == null ? null : Date.valueOf(r.updatedThrough()), r.signalId(), r.variant());
    }

    /** 未平仓（含待入场）的账本行。 */
    public List<SignalTrackRow> unfinished() {
        return jdbc.query("SELECT * FROM signal_track WHERE status <> 'CLOSED' ORDER BY signal_id, variant", MAPPER);
    }

    public List<SignalTrackRow> bySignal(long signalId) {
        return jdbc.query("SELECT * FROM signal_track WHERE signal_id = ? ORDER BY variant", MAPPER, signalId);
    }

    /** 某变体的账本，可按状态过滤；按信号判定日倒序。 */
    public List<SignalTrackRow> list(String variant, String status) {
        StringBuilder sql = new StringBuilder("""
                SELECT t.* FROM signal_track t JOIN entry_signal s ON s.id = t.signal_id WHERE 1 = 1""");
        List<Object> args = new ArrayList<>();
        if (variant != null) {
            sql.append(" AND t.variant = ?");
            args.add(variant);
        }
        if (status != null) {
            sql.append(" AND t.status = ?");
            args.add(status);
        }
        sql.append(" ORDER BY s.trade_date DESC, t.signal_id, t.variant");
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    /** 未平仓且还没算到 date 的条数（审计用）。 */
    public long staleUnfinished(LocalDate date) {
        Long n = jdbc.queryForObject("""
                SELECT count(*) FROM signal_track t JOIN entry_signal s ON s.id = t.signal_id
                WHERE t.status <> 'CLOSED' AND s.trade_date < ? AND (t.updated_through IS NULL OR t.updated_through < ?)""",
                Long.class, Date.valueOf(date), Date.valueOf(date));
        return n == null ? 0 : n;
    }
}
