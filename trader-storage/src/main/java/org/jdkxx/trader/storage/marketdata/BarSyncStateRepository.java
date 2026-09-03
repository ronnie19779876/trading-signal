package org.jdkxx.trader.storage.marketdata;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class BarSyncStateRepository {

    private static final RowMapper<BarSyncState> MAPPER = (rs, i) -> new BarSyncState(rs.getLong("instrument_id"),
            rs.getString("depth"),
            rs.getDate("earliest_date") == null ? null : rs.getDate("earliest_date").toLocalDate(),
            rs.getDate("latest_date") == null ? null : rs.getDate("latest_date").toLocalDate(),
            rs.getInt("bar_count"),
            rs.getTimestamp("last_success_at") == null ? null : rs.getTimestamp("last_success_at").toInstant(),
            rs.getString("last_error"),
            rs.getTimestamp("hist_quota_used_at") == null ? null : rs.getTimestamp("hist_quota_used_at").toInstant(),
            rs.getTimestamp("rehab_fetched_at") == null ? null : rs.getTimestamp("rehab_fetched_at").toInstant());

    private final JdbcTemplate jdbc;

    public BarSyncStateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<BarSyncState> find(long instrumentId) {
        return jdbc.query("SELECT * FROM bar_sync_state WHERE instrument_id = ?", MAPPER, instrumentId).stream().findFirst();
    }

    public List<BarSyncState> findAll() {
        return jdbc.query("SELECT * FROM bar_sync_state", MAPPER);
    }

    /** 成功后更新覆盖区间与深度（深度只升不降：HIST20Y 不会被 KL1000 覆盖）。 */
    public void success(long instrumentId, String depth, LocalDate earliest, LocalDate latest, int barCount, boolean usedHistQuota) {
        jdbc.update("""
                INSERT INTO bar_sync_state (instrument_id, depth, earliest_date, latest_date, bar_count, last_success_at, last_error, hist_quota_used_at)
                VALUES (?, ?, ?, ?, ?, now(), NULL, ?)
                ON CONFLICT (instrument_id) DO UPDATE SET
                    depth = CASE WHEN bar_sync_state.depth = 'HIST20Y' THEN 'HIST20Y' ELSE EXCLUDED.depth END,
                    earliest_date = LEAST(COALESCE(bar_sync_state.earliest_date, EXCLUDED.earliest_date), EXCLUDED.earliest_date),
                    latest_date = GREATEST(COALESCE(bar_sync_state.latest_date, EXCLUDED.latest_date), EXCLUDED.latest_date),
                    bar_count = EXCLUDED.bar_count, last_success_at = now(), last_error = NULL,
                    hist_quota_used_at = COALESCE(EXCLUDED.hist_quota_used_at, bar_sync_state.hist_quota_used_at), updated_at = now()""",
                instrumentId, depth, earliest == null ? null : Date.valueOf(earliest), latest == null ? null : Date.valueOf(latest),
                barCount, usedHistQuota ? Timestamp.from(Instant.now()) : null);
    }

    public void rehabFetched(long instrumentId) {
        jdbc.update("""
                INSERT INTO bar_sync_state (instrument_id, depth, rehab_fetched_at) VALUES (?, 'NONE', now())
                ON CONFLICT (instrument_id) DO UPDATE SET rehab_fetched_at = now(), updated_at = now()""", instrumentId);
    }

    /** 复权因子从未刷新或早于 olderThan 的标的 id（只在给定集合内挑）。 */
    public List<Long> rehabStale(java.util.Collection<Long> candidates, Instant olderThan) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                SELECT c.id FROM unnest(?::bigint[]) AS c(id)
                LEFT JOIN bar_sync_state s ON s.instrument_id = c.id
                WHERE s.rehab_fetched_at IS NULL OR s.rehab_fetched_at < ?
                ORDER BY s.rehab_fetched_at NULLS FIRST""",
                (rs, i) -> rs.getLong(1), candidates.toArray(Long[]::new), Timestamp.from(olderThan));
    }

    public void error(long instrumentId, String error) {
        jdbc.update("""
                INSERT INTO bar_sync_state (instrument_id, depth, last_error) VALUES (?, 'NONE', ?)
                ON CONFLICT (instrument_id) DO UPDATE SET last_error = EXCLUDED.last_error, updated_at = now()""",
                instrumentId, error);
    }
}
