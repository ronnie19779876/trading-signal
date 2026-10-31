package org.jdkxx.trader.storage.environment;

import java.util.Optional;
import org.jdkxx.trader.common.env.AppEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JDBC implementation of {@link EnvironmentCheck.MarkerStore}. It must also work before migrations
 * have run, so a missing table must not raise an error: existence is probed with {@code
 * to_regclass}, which resolves the name through the connection's {@code search_path} and returns
 * NULL instead of failing.
 */
public class JdbcMarkerStore implements EnvironmentCheck.MarkerStore {

  private final JdbcTemplate jdbc;
  private final String historyTable;

  /**
   * @param historyTable name of the Flyway history table (schema-qualified when a schema is
   *     configured)
   */
  public JdbcMarkerStore(JdbcTemplate jdbc, String historyTable) {
    this.jdbc = jdbc;
    this.historyTable = historyTable;
  }

  @Override
  public Optional<String> marker() {
    if (!exists("app_environment")) {
      return Optional.empty();
    }
    return Optional.ofNullable(
        jdbc.query(
            "SELECT name FROM app_environment WHERE id = 1",
            rs -> rs.next() ? rs.getString(1) : null));
  }

  @Override
  public boolean hasMigrationHistory() {
    return exists(historyTable);
  }

  @Override
  public String currentDatabase() {
    try {
      return jdbc.queryForObject("SELECT current_database()", String.class);
    } catch (RuntimeException e) {
      return "?";
    }
  }

  @Override
  public boolean inRecovery() {
    return Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_is_in_recovery()", Boolean.class));
  }

  @Override
  public void stamp(AppEnvironment environment, String note) {
    jdbc.update(
        "INSERT INTO app_environment (id, name, note) VALUES (1, ?, ?)", environment.name(), note);
  }

  private boolean exists(String table) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT to_regclass(CAST(? AS text)) IS NOT NULL", Boolean.class, table));
  }
}
