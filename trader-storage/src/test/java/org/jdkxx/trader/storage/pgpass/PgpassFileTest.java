package org.jdkxx.trader.storage.pgpass;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PgpassFileTest {

    @TempDir
    Path dir;

    @Test
    void 按host_port_db_user匹配并支持回环地址等价与通配() throws Exception {
        Path file = dir.resolve("pgpass");
        Files.writeString(file, """
                # comment
                localhost:5432:db_trader:trader:prod-secret
                localhost:5432:db_trader_dev:trader:dev-secret
                *:*:other:trader:wild-secret
                """);

        JdbcUrl dev = JdbcUrl.parse("jdbc:postgresql://127.0.0.1:5432/db_trader_dev").orElseThrow();
        assertThat(PgpassFile.lookup(file, dev, "trader")).contains("dev-secret");

        JdbcUrl prod = JdbcUrl.parse("jdbc:postgresql://localhost/db_trader").orElseThrow();
        assertThat(PgpassFile.lookup(file, prod, "trader")).contains("prod-secret");

        JdbcUrl other = JdbcUrl.parse("jdbc:postgresql://10.0.0.9:6543/other?ssl=true").orElseThrow();
        assertThat(PgpassFile.lookup(file, other, "trader")).contains("wild-secret");

        assertThat(PgpassFile.lookup(file, dev, "someone")).isEmpty();
        assertThat(PgpassFile.lookup(dir.resolve("missing"), dev, "trader")).isEmpty();
    }

    @Test
    void 转义的冒号与反斜杠按libpq规则处理() {
        List<String> fields = PgpassFile.split("h:5432:d:u:p\\:a\\\\ss:word");
        assertThat(fields).containsExactly("h", "5432", "d", "u", "p:a\\ss:word");
    }

    @Test
    void jdbcUrl解析默认端口() {
        assertThat(JdbcUrl.parse("jdbc:postgresql://db.internal/x")).contains(new JdbcUrl("db.internal", 5432, "x"));
        assertThat(JdbcUrl.parse("jdbc:mysql://h/x")).isEmpty();
        assertThat(JdbcUrl.parse(null)).isEmpty();
    }
}
