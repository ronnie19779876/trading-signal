package org.jdkxx.trader.storage.valuation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class SotpModelRepository {

    private static final String SELECT =
            "SELECT m.*, i.symbol FROM sotp_model m JOIN instrument i ON i.id = m.instrument_id";

    private static final RowMapper<SotpModelRow> MAPPER = (rs, n) -> new SotpModelRow(
            rs.getLong("id"), rs.getLong("instrument_id"), rs.getString("symbol"), rs.getString("name"),
            rs.getDate("as_of").toLocalDate(), rs.getInt("target_year"), rs.getBigDecimal("discount_rate"),
            rs.getBigDecimal("target_shares"), rs.getBigDecimal("target_net_cash"), rs.getString("segments"),
            rs.getString("note"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());

    private final JdbcTemplate jdbc;

    public SotpModelRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<SotpModelRow> findByInstrument(long instrumentId) {
        return jdbc.query(SELECT + " WHERE m.instrument_id = ? ORDER BY m.updated_at DESC", MAPPER, instrumentId);
    }

    public Optional<SotpModelRow> findById(long id) {
        return jdbc.query(SELECT + " WHERE m.id = ?", MAPPER, id).stream().findFirst();
    }

    /** 按（标的, 名称）覆盖写；已存在就更新，返回行 id。 */
    public long upsert(SotpModelRow r) {
        return jdbc.queryForObject("""
                INSERT INTO sotp_model (instrument_id, name, as_of, target_year, discount_rate,
                                        target_shares, target_net_cash, segments, note)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                ON CONFLICT (instrument_id, name) DO UPDATE SET
                    as_of = EXCLUDED.as_of, target_year = EXCLUDED.target_year, discount_rate = EXCLUDED.discount_rate,
                    target_shares = EXCLUDED.target_shares, target_net_cash = EXCLUDED.target_net_cash,
                    segments = EXCLUDED.segments, note = EXCLUDED.note, updated_at = now()
                RETURNING id
                """, Long.class, r.instrumentId(), r.name(), Date.valueOf(r.asOf()), r.targetYear(), r.discountRate(),
                r.targetShares(), r.targetNetCash(), r.segments(), r.note());
    }

    public int delete(long id) {
        return jdbc.update("DELETE FROM sotp_model WHERE id = ?", id);
    }
}
