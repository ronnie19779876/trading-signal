package org.jdkxx.trader.storage.pgpass;

import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * 数据库密码的第三条来源：{@code spring.datasource.password} 为空时，按 libpq 的规则从
 * {@code $PGPASSFILE} 或 {@code ~/.pgpass} 里查找 host:port:database:username 对应的密码。
 *
 * <p>目的：密码既不进仓库、也不必复制到第二个地方——psql 与应用共用同一份 .pgpass。
 * 显式配置（环境变量 / secrets.yml）仍然优先，只有留空时才回落到这里。
 * 用 {@code trader.storage.pgpass.enabled=false} 可关闭。永远不记录密码本身。
 */
public class PgpassEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String PROPERTY_SOURCE_NAME = "pgpass";

    private final Log log;

    public PgpassEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(PgpassEnvironmentPostProcessor.class);
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.getProperty("trader.storage.pgpass.enabled", Boolean.class, true)) {
            return;
        }
        String password = environment.getProperty("spring.datasource.password");
        if (password != null && !password.isBlank()) {
            return;
        }
        String username = environment.getProperty("spring.datasource.username");
        Optional<JdbcUrl> url = JdbcUrl.parse(environment.getProperty("spring.datasource.url"));
        if (url.isEmpty() || username == null || username.isBlank()) {
            return;
        }

        Path file = pgpassFile();
        Optional<String> found = PgpassFile.lookup(file, url.get(), username);
        if (found.isEmpty()) {
            log.info("spring.datasource.password 为空且 " + file + " 中没有匹配 "
                    + username + "@" + url.get().database() + " 的记录，保持未配置");
            return;
        }
        // 只有密码为空才走到这里，所以放在最前面覆盖那个空值是安全的
        environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME,
                Map.of("spring.datasource.password", found.get())));
        log.info("spring.datasource.password 为空，已从 " + file + " 读取 " + username + "@" + url.get().database() + " 的密码");
    }

    private static Path pgpassFile() {
        String override = System.getenv("PGPASSFILE");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        return Path.of(System.getProperty("user.home"), ".pgpass");
    }

    @Override
    public int getOrder() {
        // 必须晚于 ConfigDataEnvironmentPostProcessor（它加载 application.yml / secrets.yml）
        return Ordered.LOWEST_PRECEDENCE;
    }
}
