package org.jdkxx.trader.storage.marketdata;

import java.util.List;
import java.util.Optional;
import org.jdkxx.trader.domain.PoolRole;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Pool members ({@code pool_member}): POOL (manual candidates), HOLDING (positions, maintained from
 * IBKR since phase 3) and BENCHMARK (collected only, not part of stock selection).
 */
@Repository
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class PoolRepository {

  private static final RowMapper<PoolRow> MAPPER =
      (rs, i) ->
          new PoolRow(
              rs.getLong("instrument_id"),
              PoolRole.valueOf(rs.getString("role")),
              rs.getString("note"),
              rs.getTimestamp("added_at").toInstant());

  private final JdbcTemplate jdbc;

  public PoolRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<PoolRow> findAll() {
    return jdbc.query("SELECT * FROM pool_member ORDER BY role, added_at", MAPPER);
  }

  public Optional<PoolRow> find(long instrumentId) {
    return jdbc
        .query("SELECT * FROM pool_member WHERE instrument_id = ?", MAPPER, instrumentId)
        .stream()
        .findFirst();
  }

  public int count(PoolRole role) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM pool_member WHERE role = ?", Integer.class, role.name());
    return n == null ? 0 : n;
  }

  /**
   * Manual add or role change; a null note keeps the existing one. Source and return role are left
   * untouched.
   */
  public void upsert(long instrumentId, PoolRole role, String note) {
    jdbc.update(
        """
                INSERT INTO pool_member (instrument_id, role, note) VALUES (?, ?, ?)
                ON CONFLICT (instrument_id) DO UPDATE SET role = EXCLUDED.role, note = COALESCE(EXCLUDED.note, pool_member.note)""",
        instrumentId,
        role.name(),
        note);
  }

  public boolean delete(long instrumentId) {
    return jdbc.update("DELETE FROM pool_member WHERE instrument_id = ?", instrumentId) > 0;
  }

  // ------------------------------------------------------------------ holdings sync (phase 3):
  // every statement carries a state condition, so a member that was changed elsewhere in the
  // meantime is left alone (optimistic, no locks)

  /** Pool member with its source and previous role. */
  public record SyncRow(long instrumentId, PoolRole role, String source, String returnRole) {}

  public List<SyncRow> findAllForSync() {
    return jdbc.query(
        "SELECT instrument_id, role, source, return_role FROM pool_member",
        (rs, i) ->
            new SyncRow(
                rs.getLong(1),
                PoolRole.valueOf(rs.getString(2)),
                rs.getString(3),
                rs.getString(4)));
  }

  /**
   * Adds a position that is not in the pool as HOLDING (source IBKR); if it is already in the pool
   * nothing changes and false is returned.
   */
  public boolean addHolding(long instrumentId, String note) {
    return jdbc.update(
            """
                INSERT INTO pool_member (instrument_id, role, note, source) VALUES (?, 'HOLDING', ?, 'IBKR')
                ON CONFLICT (instrument_id) DO NOTHING""",
            instrumentId,
            note)
        == 1;
  }

  /**
   * Promotes POOL to HOLDING and remembers the previous role; does nothing unless the role is POOL.
   */
  public boolean promoteToHolding(long instrumentId) {
    return jdbc.update(
            "UPDATE pool_member SET role = 'HOLDING', return_role = 'POOL' WHERE instrument_id = ? AND role = 'POOL'",
            instrumentId)
        == 1;
  }

  /**
   * After the position is closed, returns to the previous role POOL; does nothing unless it is "a
   * HOLDING that used to be POOL".
   */
  public boolean returnToPool(long instrumentId) {
    return jdbc.update(
            """
                UPDATE pool_member SET role = 'POOL', return_role = NULL
                WHERE instrument_id = ? AND role = 'HOLDING' AND return_role = 'POOL'""",
            instrumentId)
        == 1;
  }

  /**
   * Removes the member from the pool after the position is closed; only HOLDING rows are deleted.
   */
  public boolean deleteHolding(long instrumentId) {
    return jdbc.update(
            "DELETE FROM pool_member WHERE instrument_id = ? AND role = 'HOLDING'", instrumentId)
        == 1;
  }

  public void markSource(long instrumentId, String source) {
    jdbc.update("UPDATE pool_member SET source = ? WHERE instrument_id = ?", source, instrumentId);
  }
}
