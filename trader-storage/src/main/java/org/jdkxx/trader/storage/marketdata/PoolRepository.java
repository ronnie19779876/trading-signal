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
}
