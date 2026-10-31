package org.jdkxx.trader.storage.status;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Read-only database probe: current database name, server version and environment marker. Any
 * exception is folded into {@code detail} (exception class name only, so connection details in the
 * message never reach the page) instead of failing the whole system page.
 */
@Service
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class DatabaseStatusService {

  private final JdbcTemplate jdbc;

  public DatabaseStatusService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public DatabaseStatus status() {
    try {
      String database = jdbc.queryForObject("SELECT current_database()", String.class);
      String version = jdbc.queryForObject("SHOW server_version", String.class);
      String marker =
          jdbc.query(
              "SELECT name FROM app_environment WHERE id = 1",
              rs -> rs.next() ? rs.getString(1) : null);
      return new DatabaseStatus(true, database, version, marker, "OK");
    } catch (RuntimeException e) {
      return new DatabaseStatus(true, null, null, null, "ERROR: " + e.getClass().getSimpleName());
    }
  }
}
