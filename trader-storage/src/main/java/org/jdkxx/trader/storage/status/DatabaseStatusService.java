package org.jdkxx.trader.storage.status;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 只读探测数据库：当前库名、服务器版本、环境标记。任何异常都折叠成 detail，不让系统页整体失败。
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
            String marker = jdbc.query("SELECT name FROM app_environment WHERE id = 1",
                    rs -> rs.next() ? rs.getString(1) : null);
            return new DatabaseStatus(true, database, version, marker, "OK");
        } catch (RuntimeException e) {
            return new DatabaseStatus(true, null, null, null, "ERROR: " + e.getClass().getSimpleName());
        }
    }
}
