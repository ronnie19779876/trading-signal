package org.jdkxx.trader.storage.environment;

import org.jdkxx.trader.common.env.AppEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

/**
 * {@link EnvironmentCheck.MarkerStore} 的 JDBC 实现。在迁移之前也要能调用，所以表不存在时不能报错。
 */
public class JdbcMarkerStore implements EnvironmentCheck.MarkerStore {

    private final JdbcTemplate jdbc;
    private final String historyTable;

    /** @param historyTable Flyway 迁移记录表名（配置了 schema 时带前缀） */
    public JdbcMarkerStore(JdbcTemplate jdbc, String historyTable) {
        this.jdbc = jdbc;
        this.historyTable = historyTable;
    }

    @Override
    public Optional<String> marker() {
        if (!exists("app_environment")) {
            return Optional.empty();
        }
        return Optional.ofNullable(jdbc.query("SELECT name FROM app_environment WHERE id = 1",
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
    public void stamp(AppEnvironment environment, String note) {
        jdbc.update("INSERT INTO app_environment (id, name, note) VALUES (1, ?, ?)", environment.name(), note);
    }

    private boolean exists(String table) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regclass(CAST(? AS text)) IS NOT NULL", Boolean.class, table));
    }
}
