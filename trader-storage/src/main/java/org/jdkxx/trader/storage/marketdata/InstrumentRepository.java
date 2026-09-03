package org.jdkxx.trader.storage.marketdata;

import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.InstrumentStatic;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.SecurityType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class InstrumentRepository {

    private static final String COLS = "id, market, symbol, name, name_cn, sec_type, lot_size, list_date, delisted, exchange, broker_id, resolve_status";
    private static final RowMapper<InstrumentRow> MAPPER = (rs, i) -> new InstrumentRow(
            rs.getLong("id"), Market.valueOf(rs.getString("market")), rs.getString("symbol"), rs.getString("name"),
            rs.getString("name_cn"), SecurityType.valueOf(rs.getString("sec_type")),
            rs.getObject("lot_size") == null ? null : rs.getInt("lot_size"),
            rs.getDate("list_date") == null ? null : rs.getDate("list_date").toLocalDate(),
            rs.getBoolean("delisted"), rs.getString("exchange"),
            rs.getObject("broker_id") == null ? null : rs.getLong("broker_id"), rs.getString("resolve_status"));

    private final JdbcTemplate jdbc;

    public InstrumentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按 (market, symbol) 幂等插入；已存在时只在 name 为空时补 name。返回 id。 */
    public long upsert(Instrument instrument, String name) {
        return jdbc.queryForObject("""
                INSERT INTO instrument (market, symbol, name) VALUES (?, ?, ?)
                ON CONFLICT (market, symbol) DO UPDATE SET name = COALESCE(instrument.name, EXCLUDED.name), updated_at = now()
                RETURNING id""", Long.class, instrument.market().name(), instrument.symbol(), name);
    }

    public Optional<InstrumentRow> find(Instrument instrument) {
        return jdbc.query("SELECT " + COLS + " FROM instrument WHERE market = ? AND symbol = ?", MAPPER,
                instrument.market().name(), instrument.symbol()).stream().findFirst();
    }

    public Optional<InstrumentRow> findById(long id) {
        return jdbc.query("SELECT " + COLS + " FROM instrument WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public List<InstrumentRow> findByIds(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.query("SELECT " + COLS + " FROM instrument WHERE id = ANY (?) ORDER BY symbol", MAPPER,
                (Object) ids.toArray(Long[]::new));
    }

    public List<InstrumentRow> findAll() {
        return jdbc.query("SELECT " + COLS + " FROM instrument ORDER BY market, symbol", MAPPER);
    }

    public void updateStatic(long id, InstrumentStatic s) {
        jdbc.update("""
                UPDATE instrument SET name_cn = ?, sec_type = ?, lot_size = ?, list_date = ?, delisted = ?, exchange = ?,
                       broker_id = ?, resolve_status = 'RESOLVED', updated_at = now() WHERE id = ?""",
                s.name(), s.type().name(), s.lotSize(), s.listDate() == null ? null : Date.valueOf(s.listDate()),
                s.delisted(), s.exchange(), s.brokerId(), id);
    }

    public void markUnresolved(long id) {
        jdbc.update("UPDATE instrument SET resolve_status = 'UNRESOLVED', updated_at = now() WHERE id = ?", id);
    }

    public long count() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM instrument", Long.class);
        return n == null ? 0 : n;
    }
}
