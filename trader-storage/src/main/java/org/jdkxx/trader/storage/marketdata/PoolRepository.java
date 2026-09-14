package org.jdkxx.trader.storage.marketdata;

import org.jdkxx.trader.domain.PoolRole;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class PoolRepository {

    private static final RowMapper<PoolRow> MAPPER = (rs, i) -> new PoolRow(rs.getLong("instrument_id"),
            PoolRole.valueOf(rs.getString("role")), rs.getString("note"), rs.getTimestamp("added_at").toInstant());

    private final JdbcTemplate jdbc;

    public PoolRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<PoolRow> findAll() {
        return jdbc.query("SELECT * FROM pool_member ORDER BY role, added_at", MAPPER);
    }

    public Optional<PoolRow> find(long instrumentId) {
        return jdbc.query("SELECT * FROM pool_member WHERE instrument_id = ?", MAPPER, instrumentId).stream().findFirst();
    }

    public int count(PoolRole role) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM pool_member WHERE role = ?", Integer.class, role.name());
        return n == null ? 0 : n;
    }

    public void upsert(long instrumentId, PoolRole role, String note) {
        jdbc.update("""
                INSERT INTO pool_member (instrument_id, role, note) VALUES (?, ?, ?)
                ON CONFLICT (instrument_id) DO UPDATE SET role = EXCLUDED.role, note = COALESCE(EXCLUDED.note, pool_member.note)""",
                instrumentId, role.name(), note);
    }

    public boolean delete(long instrumentId) {
        return jdbc.update("DELETE FROM pool_member WHERE instrument_id = ?", instrumentId) > 0;
    }

    // ------------------------------------------------------------------ 持仓同步（第 3 期）：每处都带状态条件，池被别处改过就不动

    /** 带来源与原角色的池成员。 */
    public record SyncRow(long instrumentId, PoolRole role, String source, String returnRole) {
    }

    public List<SyncRow> findAllForSync() {
        return jdbc.query("SELECT instrument_id, role, source, return_role FROM pool_member",
                (rs, i) -> new SyncRow(rs.getLong(1), PoolRole.valueOf(rs.getString(2)), rs.getString(3), rs.getString(4)));
    }

    /** 不在池里的持仓加为 HOLDING（来源 IBKR）；已在池里则不动，返回 false。 */
    public boolean addHolding(long instrumentId, String note) {
        return jdbc.update("""
                INSERT INTO pool_member (instrument_id, role, note, source) VALUES (?, 'HOLDING', ?, 'IBKR')
                ON CONFLICT (instrument_id) DO NOTHING""", instrumentId, note) == 1;
    }

    /** POOL 升为 HOLDING，记下原角色；不是 POOL 时不动。 */
    public boolean promoteToHolding(long instrumentId) {
        return jdbc.update("UPDATE pool_member SET role = 'HOLDING', return_role = 'POOL' WHERE instrument_id = ? AND role = 'POOL'",
                instrumentId) == 1;
    }

    /** 清仓后回到原角色 POOL；不是"原为 POOL 的 HOLDING"时不动。 */
    public boolean returnToPool(long instrumentId) {
        return jdbc.update("""
                UPDATE pool_member SET role = 'POOL', return_role = NULL
                WHERE instrument_id = ? AND role = 'HOLDING' AND return_role = 'POOL'""", instrumentId) == 1;
    }

    /** 清仓后移出池；只删 HOLDING。 */
    public boolean deleteHolding(long instrumentId) {
        return jdbc.update("DELETE FROM pool_member WHERE instrument_id = ? AND role = 'HOLDING'", instrumentId) == 1;
    }

    public void markSource(long instrumentId, String source) {
        jdbc.update("UPDATE pool_member SET source = ? WHERE instrument_id = ?", source, instrumentId);
    }
}
