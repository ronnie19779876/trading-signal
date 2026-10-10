package org.jdkxx.trader.storage.selection;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 量化选股输入回补的逐只进度（§25.7）。续跑据此跳过已完成的。 */
@Repository
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class SelectionBackfillStateRepository {

    public record StateRow(long instrumentId, String symbol, String part, String status, String detail, Instant updatedAt) {
    }

    public record Count(String part, String status, long n) {
    }

    private static final RowMapper<StateRow> MAPPER = (rs, n) -> new StateRow(rs.getLong("instrument_id"),
            rs.getString("symbol"), rs.getString("part"), rs.getString("status"), rs.getString("detail"),
            rs.getTimestamp("updated_at").toInstant());

    private final JdbcTemplate jdbc;

    public SelectionBackfillStateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(long instrumentId, String part, String status, String detail) {
        jdbc.update("""
                INSERT INTO selection_backfill_state (instrument_id, part, status, detail) VALUES (?, ?, ?, ?)
                ON CONFLICT (instrument_id, part) DO UPDATE SET status = EXCLUDED.status, detail = EXCLUDED.detail,
                    updated_at = now()""", instrumentId, part, status, detail);
    }

    /** 某部分每只标的的当前状态。 */
    public Map<Long, String> statuses(String part) {
        Map<Long, String> out = new HashMap<>();
        jdbc.query("SELECT instrument_id, status FROM selection_backfill_state WHERE part = ?",
                rs -> {
                    out.put(rs.getLong(1), rs.getString(2));
                }, part);
        return out;
    }

    public List<Count> counts() {
        return jdbc.query("SELECT part, status, count(*) AS n FROM selection_backfill_state GROUP BY part, status ORDER BY part, status",
                (rs, n) -> new Count(rs.getString("part"), rs.getString("status"), rs.getLong("n")));
    }

    /** 某状态的明细（审计列失败、空回清单用），按代码排序。 */
    public List<StateRow> findByStatus(String status, int limit) {
        return jdbc.query("""
                SELECT s.*, i.symbol FROM selection_backfill_state s JOIN instrument i ON i.id = s.instrument_id
                WHERE s.status = ? ORDER BY s.part, i.symbol LIMIT ?""", MAPPER, status, limit);
    }
}
