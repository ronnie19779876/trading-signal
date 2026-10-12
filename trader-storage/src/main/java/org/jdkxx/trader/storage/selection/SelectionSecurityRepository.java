package org.jdkxx.trader.storage.selection;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.Map;

/** 证券类型（盈透 stockType，§25.3 第 6 条）。 */
@Repository
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class SelectionSecurityRepository {

    private final JdbcTemplate jdbc;

    public SelectionSecurityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(long instrumentId, String securityType, Long conId) {
        jdbc.update("""
                INSERT INTO selection_security (instrument_id, security_type, ibkr_con_id) VALUES (?, ?, ?)
                ON CONFLICT (instrument_id) DO UPDATE SET security_type = EXCLUDED.security_type,
                    ibkr_con_id = EXCLUDED.ibkr_con_id, checked_at = now()""", instrumentId, securityType, conId);
    }

    /** 标的 → 证券类型；类型为 NULL（券商没给）的标的不放进结果，取不到即「判不出来」。 */
    public Map<Long, String> types() {
        Map<Long, String> out = new HashMap<>();
        jdbc.query("SELECT instrument_id, security_type FROM selection_security WHERE security_type IS NOT NULL",
                rs -> {
                    out.put(rs.getLong(1), rs.getString(2));
                });
        return out;
    }
}
