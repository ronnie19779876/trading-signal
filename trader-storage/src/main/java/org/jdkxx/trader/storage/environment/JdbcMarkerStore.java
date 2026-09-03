package org.jdkxx.trader.storage.environment;

import org.jdkxx.trader.common.env.AppEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

/**
 * {@link EnvironmentCheck.MarkerStore} 的 JDBC 实现。
 */
public class JdbcMarkerStore implements EnvironmentCheck.MarkerStore {

    private final JdbcTemplate jdbc;

    public JdbcMarkerStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> marker() {
        return Optional.ofNullable(jdbc.query("SELECT name FROM app_environment WHERE id = 1",
                rs -> rs.next() ? rs.getString(1) : null));
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
    public void stamp(AppEnvironment environment, String note) {
        jdbc.update("INSERT INTO app_environment (id, name, note) VALUES (1, ?, ?)", environment.name(), note);
    }
}
