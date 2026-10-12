package org.jdkxx.trader.storage.selection;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 量化选股·模拟交易（§26）：账户、逐笔、留痕、逐日权益、周报。现金与权益不存余额，由逐笔推出来。 */
@Repository
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class SelectionSimRepository {

    public record Account(String rulesVersion, BigDecimal initialCapital, Instant createdAt) {
    }

    public record Event(long id, Long tradeId, String symbol, Instant at, String kind, String detail) {
    }

    public record EquityRow(LocalDate tradeDate, BigDecimal cash, BigDecimal positionsValue, BigDecimal equity, int openPositions) {
    }

    public record WeeklyRow(LocalDate weekEnd, LocalDate weekStart, String rulesVersion, BigDecimal equityEnd, BigDecimal weekReturn,
                            BigDecimal spyWeekReturn, int closedTrades, String report, Instant generatedAt) {
    }

    private static final String COLUMNS = """
            rules_version, instrument_id, model_version, score_date, score_rank, rating, composite, requested_at,
            setup_close, setup_atr, formula_stop, stop, stop_leg, resistance, planned_entry_date,
            status, entry_date, entry_price, shares, plus_one_r, target, target_source, touched_plus_one_r, target_hit_date,
            active_stop, chandelier_active, close_requested_at, planned_exit_date, close_kind, close_note,
            exit_date, exit_price, exit_reason, pnl, return_pct, r_multiple, mfe_r, mae_r, bars_held, last_close, last_close_date,
            unrealized_pnl, spy_return, excess_return,
            shadow_status, shadow_exit_date, shadow_exit_price, shadow_exit_reason, shadow_pnl, shadow_return, shadow_last_close,
            cancel_reason, updated_through""";

    private static final RowMapper<SimTradeRow> TRADE = (rs, n) -> new SimTradeRow(
            rs.getLong("id"), rs.getString("rules_version"), rs.getLong("instrument_id"), rs.getString("symbol"),
            rs.getString("model_version"), date(rs, "score_date"), rs.getInt("score_rank"), rs.getString("rating"),
            rs.getBigDecimal("composite"), instant(rs, "requested_at"),
            rs.getBigDecimal("setup_close"), rs.getBigDecimal("setup_atr"), rs.getBigDecimal("formula_stop"), rs.getBigDecimal("stop"),
            rs.getString("stop_leg"), rs.getBigDecimal("resistance"), date(rs, "planned_entry_date"),
            rs.getString("status"), date(rs, "entry_date"), rs.getBigDecimal("entry_price"), (Integer) rs.getObject("shares"),
            rs.getBigDecimal("plus_one_r"), rs.getBigDecimal("target"), rs.getString("target_source"), rs.getBoolean("touched_plus_one_r"),
            date(rs, "target_hit_date"), rs.getBigDecimal("active_stop"), rs.getBoolean("chandelier_active"),
            instant(rs, "close_requested_at"), date(rs, "planned_exit_date"), rs.getString("close_kind"), rs.getString("close_note"),
            date(rs, "exit_date"), rs.getBigDecimal("exit_price"), rs.getString("exit_reason"), rs.getBigDecimal("pnl"),
            rs.getBigDecimal("return_pct"), rs.getBigDecimal("r_multiple"), rs.getBigDecimal("mfe_r"), rs.getBigDecimal("mae_r"),
            (Integer) rs.getObject("bars_held"), rs.getBigDecimal("last_close"), date(rs, "last_close_date"),
            rs.getBigDecimal("unrealized_pnl"), rs.getBigDecimal("spy_return"), rs.getBigDecimal("excess_return"),
            rs.getString("shadow_status"), date(rs, "shadow_exit_date"), rs.getBigDecimal("shadow_exit_price"),
            rs.getString("shadow_exit_reason"), rs.getBigDecimal("shadow_pnl"), rs.getBigDecimal("shadow_return"),
            rs.getBigDecimal("shadow_last_close"),
            rs.getString("cancel_reason"), date(rs, "updated_through"), instant(rs, "updated_at"));

    private static final String SELECT = "SELECT t.*, i.symbol FROM selection_sim_trade t JOIN instrument i ON i.id = t.instrument_id";

    private final JdbcTemplate jdbc;

    public SelectionSimRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Account account() {
        return jdbc.queryForObject("SELECT rules_version, initial_capital, created_at FROM selection_sim_account WHERE id = 1",
                (rs, n) -> new Account(rs.getString(1), rs.getBigDecimal(2), rs.getTimestamp(3).toInstant()));
    }

    /** 新增一笔，返回 id。同一只已有进行中的一笔时违反唯一索引（调用方先查、这里兜底）。 */
    public long insert(SimTradeRow t) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        String placeholders = "?" + ", ?".repeat(COLUMNS.split(",").length - 1);
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("INSERT INTO selection_sim_trade (" + COLUMNS + ") VALUES (" + placeholders + ")",
                    new String[] {"id"});
            Object[] args = values(t);
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /** 整行覆盖（id 不变）。 */
    public void update(SimTradeRow t) {
        String set = String.join(", ", java.util.Arrays.stream(COLUMNS.split(",")).map(c -> c.trim() + " = ?").toList());
        Object[] v = values(t);
        Object[] args = java.util.Arrays.copyOf(v, v.length + 1);
        args[v.length] = t.id();
        jdbc.update("UPDATE selection_sim_trade SET " + set + ", updated_at = now() WHERE id = ?", args);
    }

    public Optional<SimTradeRow> find(long id) {
        return jdbc.query(SELECT + " WHERE t.id = ?", TRADE, id).stream().findFirst();
    }

    /** 全部，按加入时间倒序；status 为空不过滤。 */
    public List<SimTradeRow> list(String status) {
        return status == null || status.isBlank()
                ? jdbc.query(SELECT + " ORDER BY t.requested_at DESC, t.id DESC", TRADE)
                : jdbc.query(SELECT + " WHERE t.status = ? ORDER BY t.requested_at DESC, t.id DESC", TRADE, status);
    }

    /** 已入场过的（持有中与已平仓），按入场先后：算现金与权益用。 */
    public List<SimTradeRow> filled() {
        return jdbc.query(SELECT + " WHERE t.entry_date IS NOT NULL AND t.shares IS NOT NULL ORDER BY t.entry_date, t.requested_at, t.id", TRADE);
    }

    /** 每天要重算的：待成交、持有中，或影子变体还没平仓。待成交按入场日、请求时间先后（决定同一天谁先用现金）。 */
    public List<SimTradeRow> unfinished() {
        return jdbc.query(SELECT + " WHERE t.status IN ('PENDING_ENTRY', 'OPEN') OR (t.status = 'CLOSED' AND t.shadow_status = 'OPEN')"
                + " ORDER BY t.planned_entry_date, t.requested_at, t.id", TRADE);
    }

    public int activeCount() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM selection_sim_trade WHERE status IN ('PENDING_ENTRY', 'OPEN')", Integer.class);
        return n == null ? 0 : n;
    }

    public Optional<SimTradeRow> activeFor(long instrumentId) {
        return jdbc.query(SELECT + " WHERE t.instrument_id = ? AND t.status IN ('PENDING_ENTRY', 'OPEN')", TRADE, instrumentId)
                .stream().findFirst();
    }

    public void event(Long tradeId, String kind, String detail) {
        jdbc.update("INSERT INTO selection_sim_event (trade_id, kind, detail) VALUES (?, ?, ?)", tradeId, kind, detail);
    }

    /** 留痕，新的在前；tradeId 为空看全部。 */
    public List<Event> events(Long tradeId, int limit) {
        String sql = """
                SELECT e.id, e.trade_id, i.symbol, e.at, e.kind, e.detail FROM selection_sim_event e
                LEFT JOIN selection_sim_trade t ON t.id = e.trade_id LEFT JOIN instrument i ON i.id = t.instrument_id""";
        RowMapper<Event> m = (rs, n) -> new Event(rs.getLong(1), (Long) rs.getObject(2), rs.getString(3), rs.getTimestamp(4).toInstant(),
                rs.getString(5), rs.getString(6));
        return tradeId == null
                ? jdbc.query(sql + " ORDER BY e.at DESC, e.id DESC LIMIT ?", m, limit)
                : jdbc.query(sql + " WHERE e.trade_id = ? ORDER BY e.at DESC, e.id DESC LIMIT ?", m, tradeId, limit);
    }

    public void upsertEquity(List<EquityRow> rows) {
        jdbc.batchUpdate("""
                INSERT INTO selection_sim_equity (trade_date, cash, positions_value, equity, open_positions) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (trade_date) DO UPDATE SET cash = EXCLUDED.cash, positions_value = EXCLUDED.positions_value,
                    equity = EXCLUDED.equity, open_positions = EXCLUDED.open_positions, computed_at = now()""",
                rows.stream().map(r -> new Object[] {Date.valueOf(r.tradeDate()), r.cash(), r.positionsValue(), r.equity(), r.openPositions()})
                        .toList());
    }

    public List<EquityRow> equity(LocalDate from, LocalDate to) {
        return jdbc.query("SELECT * FROM selection_sim_equity WHERE trade_date BETWEEN ? AND ? ORDER BY trade_date",
                (rs, n) -> new EquityRow(rs.getDate("trade_date").toLocalDate(), rs.getBigDecimal("cash"), rs.getBigDecimal("positions_value"),
                        rs.getBigDecimal("equity"), rs.getInt("open_positions")), Date.valueOf(from), Date.valueOf(to));
    }

    /** 新增或覆盖一周的报表。 */
    public void saveWeekly(WeeklyRow w) {
        jdbc.update("""
                INSERT INTO selection_sim_weekly (week_end, week_start, rules_version, equity_end, week_return, spy_week_return, closed_trades, report)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (week_end) DO UPDATE SET week_start = EXCLUDED.week_start, rules_version = EXCLUDED.rules_version,
                    equity_end = EXCLUDED.equity_end, week_return = EXCLUDED.week_return, spy_week_return = EXCLUDED.spy_week_return,
                    closed_trades = EXCLUDED.closed_trades, report = EXCLUDED.report, generated_at = now()""",
                Date.valueOf(w.weekEnd()), Date.valueOf(w.weekStart()), w.rulesVersion(), w.equityEnd(), w.weekReturn(), w.spyWeekReturn(),
                w.closedTrades(), w.report());
    }

    private static final RowMapper<WeeklyRow> WEEKLY = (rs, n) -> new WeeklyRow(rs.getDate("week_end").toLocalDate(),
            rs.getDate("week_start").toLocalDate(), rs.getString("rules_version"), rs.getBigDecimal("equity_end"),
            rs.getBigDecimal("week_return"), rs.getBigDecimal("spy_week_return"), rs.getInt("closed_trades"), rs.getString("report"),
            rs.getTimestamp("generated_at").toInstant());

    public Optional<WeeklyRow> weekly(LocalDate weekEnd) {
        return jdbc.query("SELECT * FROM selection_sim_weekly WHERE week_end = ?", WEEKLY, Date.valueOf(weekEnd)).stream().findFirst();
    }

    /** 周报列表（新的在前），不带正文以外的大字段之外全给。 */
    public List<WeeklyRow> weeklies(int limit) {
        return jdbc.query("SELECT * FROM selection_sim_weekly ORDER BY week_end DESC LIMIT ?", WEEKLY, limit);
    }

    private static Object[] values(SimTradeRow t) {
        return new Object[] {t.rulesVersion(), t.instrumentId(), t.modelVersion(), d(t.scoreDate()), t.scoreRank(), t.rating(), t.composite(),
                ts(t.requestedAt()), t.setupClose(), t.setupAtr(), t.formulaStop(), t.stop(), t.stopLeg(), t.resistance(),
                d(t.plannedEntryDate()), t.status(), d(t.entryDate()), t.entryPrice(), t.shares(), t.plusOneR(), t.target(),
                t.targetSource(), t.touchedPlusOneR(), d(t.targetHitDate()), t.activeStop(), t.chandelierActive(), ts(t.closeRequestedAt()),
                d(t.plannedExitDate()), t.closeKind(), t.closeNote(), d(t.exitDate()), t.exitPrice(), t.exitReason(), t.pnl(),
                t.returnPct(), t.rMultiple(), t.mfeR(), t.maeR(), t.barsHeld(), t.lastClose(), d(t.lastCloseDate()), t.unrealizedPnl(),
                t.spyReturn(), t.excessReturn(), t.shadowStatus(), d(t.shadowExitDate()), t.shadowExitPrice(), t.shadowExitReason(),
                t.shadowPnl(), t.shadowReturn(), t.shadowLastClose(), t.cancelReason(), d(t.updatedThrough())};
    }

    private static Date d(LocalDate x) {
        return x == null ? null : Date.valueOf(x);
    }

    private static Timestamp ts(Instant x) {
        return x == null ? null : Timestamp.from(x);
    }

    private static LocalDate date(ResultSet rs, String col) throws SQLException {
        Date x = rs.getDate(col);
        return x == null ? null : x.toLocalDate();
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        Timestamp x = rs.getTimestamp(col);
        return x == null ? null : x.toInstant();
    }
}
