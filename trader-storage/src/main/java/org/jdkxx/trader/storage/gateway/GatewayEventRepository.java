package org.jdkxx.trader.storage.gateway;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.jdkxx.trader.domain.Broker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The gateway_event table. Only inserts and "latest N" queries; rows are never deleted so the
 * timeline is preserved.
 */
@Repository
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class GatewayEventRepository {

  private final JdbcTemplate jdbc;

  public GatewayEventRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(Broker broker, String event, String detail, Instant occurredAt) {
    jdbc.update(
        "INSERT INTO gateway_event (broker, event, detail, occurred_at) VALUES (?, ?, ?, ?)",
        broker.name(),
        event,
        detail,
        Timestamp.from(occurredAt));
  }

  public List<GatewayEventRecord> latest(int limit) {
    return jdbc.query(
        "SELECT id, broker, event, detail, occurred_at FROM gateway_event ORDER BY occurred_at DESC, id DESC LIMIT ?",
        (rs, i) ->
            new GatewayEventRecord(
                rs.getLong("id"),
                Broker.valueOf(rs.getString("broker")),
                rs.getString("event"),
                rs.getString("detail"),
                rs.getTimestamp("occurred_at").toInstant()),
        Math.max(1, Math.min(limit, 500)));
  }
}
