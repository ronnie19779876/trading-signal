package org.jdkxx.trader.storage.signal;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Paper ledger ({@code signal_track}): one row per signal and exit variant, PENDING_ENTRY → OPEN →
 * CLOSED. Prices are in decision-day terms (scaled back through the same factor as the entry), so
 * they can be subtracted across splits.
 */
@Repository
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class SignalTrackRepository {

  private static final RowMapper<SignalTrackRow> MAPPER =
      (rs, n) ->
          new SignalTrackRow(
              rs.getLong("signal_id"),
              rs.getString("variant"),
              rs.getString("status"),
              rs.getBigDecimal("stop"),
              rs.getBigDecimal("plus_one_r"),
              date(rs.getDate("entry_date")),
              rs.getBigDecimal("entry_price"),
              rs.getBoolean("touched_plus_one_r"),
              date(rs.getDate("exit_date")),
              rs.getBigDecimal("exit_price"),
              rs.getString("exit_reason"),
              rs.getBigDecimal("r_multiple"),
              rs.getBigDecimal("return_pct"),
              rs.getBigDecimal("mfe_r"),
              rs.getBigDecimal("mae_r"),
              rs.getBigDecimal("last_close"),
              date(rs.getDate("last_close_date")),
              rs.getBigDecimal("unrealized_r"),
              rs.getObject("bars_held") == null ? null : rs.getInt("bars_held"),
              date(rs.getDate("updated_through")),
              rs.getTimestamp("updated_at").toInstant());

  private final JdbcTemplate jdbc;

  public SignalTrackRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static LocalDate date(Date d) {
    return d == null ? null : d.toLocalDate();
  }

  /** Ledger row for a new signal; left untouched if it already exists. */
  public void createIfAbsent(
      long signalId, String variant, java.math.BigDecimal stop, java.math.BigDecimal plusOneR) {
    jdbc.update(
        """
                INSERT INTO signal_track (signal_id, variant, status, stop, plus_one_r) VALUES (?, ?, 'PENDING_ENTRY', ?, ?)
                ON CONFLICT (signal_id, variant) DO NOTHING""",
        signalId,
        variant,
        stop,
        plusOneR);
  }

  /**
   * Overwrites the mutable state of one ledger row; stop and +1R are fixed at creation and not
   * updated.
   */
  public void update(SignalTrackRow r) {
    jdbc.update(
        """
                UPDATE signal_track SET status = ?, entry_date = ?, entry_price = ?, touched_plus_one_r = ?, exit_date = ?,
                       exit_price = ?, exit_reason = ?, r_multiple = ?, return_pct = ?, mfe_r = ?, mae_r = ?,
                       last_close = ?, last_close_date = ?, unrealized_r = ?, bars_held = ?,
                       updated_through = ?, updated_at = now()
                WHERE signal_id = ? AND variant = ?""",
        r.status(),
        r.entryDate() == null ? null : Date.valueOf(r.entryDate()),
        r.entryPrice(),
        r.touchedPlusOneR(),
        r.exitDate() == null ? null : Date.valueOf(r.exitDate()),
        r.exitPrice(),
        r.exitReason(),
        r.rMultiple(),
        r.returnPct(),
        r.mfeR(),
        r.maeR(),
        r.lastClose(),
        r.lastCloseDate() == null ? null : Date.valueOf(r.lastCloseDate()),
        r.unrealizedR(),
        r.barsHeld(),
        r.updatedThrough() == null ? null : Date.valueOf(r.updatedThrough()),
        r.signalId(),
        r.variant());
  }

  /** Ledger rows not yet closed (including those pending entry). */
  public List<SignalTrackRow> unfinished() {
    return jdbc.query(
        "SELECT * FROM signal_track WHERE status <> 'CLOSED' ORDER BY signal_id, variant", MAPPER);
  }

  public List<SignalTrackRow> bySignals(java.util.Collection<Long> signalIds) {
    if (signalIds.isEmpty()) {
      return List.of();
    }
    return jdbc.query(
        "SELECT * FROM signal_track WHERE signal_id = ANY (?) ORDER BY signal_id, variant",
        MAPPER,
        (Object) signalIds.toArray(Long[]::new));
  }

  public List<SignalTrackRow> bySignal(long signalId) {
    return jdbc.query(
        "SELECT * FROM signal_track WHERE signal_id = ? ORDER BY variant", MAPPER, signalId);
  }

  /**
   * The ledger of one variant, optionally filtered by status; newest decision day first. Null
   * filters are skipped.
   */
  public List<SignalTrackRow> list(String variant, String status) {
    StringBuilder sql =
        new StringBuilder(
            """
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

  /**
   * Number of unclosed rows of signals before {@code date} that have not been computed through
   * {@code date} yet (for the audit).
   */
  public long staleUnfinished(LocalDate date) {
    Long n =
        jdbc.queryForObject(
            """
                SELECT count(*) FROM signal_track t JOIN entry_signal s ON s.id = t.signal_id
                WHERE t.status <> 'CLOSED' AND s.trade_date < ? AND (t.updated_through IS NULL OR t.updated_through < ?)""",
            Long.class,
            Date.valueOf(date),
            Date.valueOf(date));
    return n == null ? 0 : n;
  }
}
