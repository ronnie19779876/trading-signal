package org.jdkxx.trader.storage.environment;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.Configuration;
import org.jdkxx.trader.common.env.AppEnvironment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Environment guard: takes over Flyway migration as the {@link FlywayMigrationStrategy}. It
 * verifies the environment marker first and only migrates if that passes.
 *
 * <p>Why this hook: Spring Boot makes every bean that depends on the DataSource/JdbcTemplate wait
 * for Flyway initialization, so the guard is guaranteed to touch the database before any business
 * code does. A failed check throws, startup aborts, and <b>not a single migration has run</b>.
 *
 * <p>Caveat: the guard only runs because Flyway runs. With Flyway auto-configuration excluded or
 * {@code spring.flyway.enabled=false}, this strategy is never invoked and nothing checks the marker
 * or the standby state.
 *
 * <p>Only wired when {@code trader.storage.enabled=true}; {@code trader.environment} is then
 * mandatory.
 */
@Component
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class EnvironmentGuard implements FlywayMigrationStrategy {

  private final AppEnvironment declared;

  public EnvironmentGuard(@Value("${trader.environment:}") String declared) {
    try {
      this.declared = AppEnvironment.parse(declared);
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException(e.getMessage(), e);
    }
  }

  /**
   * Builds the marker store from Flyway's own DataSource and history-table name so the check looks
   * at exactly the database and schema the migrations would touch.
   */
  @Override
  public void migrate(Flyway flyway) {
    Configuration c = flyway.getConfiguration();
    String schema = c.getDefaultSchema();
    String history =
        schema == null || schema.isBlank() ? c.getTable() : schema + "." + c.getTable();
    guard(new JdbcMarkerStore(new JdbcTemplate(c.getDataSource()), history), flyway::migrate);
  }

  /**
   * Verify, then migrate, then stamp an empty database. The order is the whole point of this guard,
   * so it is split out to be tested.
   */
  void guard(EnvironmentCheck.MarkerStore store, Runnable migration) {
    EnvironmentCheck.Decision decision = EnvironmentCheck.verify(store, declared);
    migration.run();
    if (decision == EnvironmentCheck.Decision.STAMP_AFTER_MIGRATION) {
      EnvironmentCheck.stampFresh(store, declared);
    }
  }

  public AppEnvironment declared() {
    return declared;
  }
}
