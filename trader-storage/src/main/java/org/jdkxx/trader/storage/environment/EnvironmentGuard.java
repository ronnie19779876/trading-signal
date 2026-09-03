package org.jdkxx.trader.storage.environment;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.jdkxx.trader.common.env.AppEnvironment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 环境守卫：以 {@link FlywayMigrationStrategy} 的身份挂在 Flyway 迁移之后立刻执行。
 *
 * <p>这样做的好处：Spring Boot 让所有依赖 DataSource/JdbcTemplate 的 bean 都等待 Flyway 初始化完成，
 * 于是守卫必然先于任何业务代码触库；校验失败抛异常，应用启动即中止。
 * 同时 {@code MigrateResult.initialSchemaVersion == null} 精确告诉我们"启动前是不是空库"。
 *
 * <p>只有 {@code trader.storage.enabled=true} 时才装配；此时 {@code trader.environment} 必填。
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

    @Override
    public void migrate(Flyway flyway) {
        MigrateResult result = flyway.migrate();
        boolean freshDatabase = result.initialSchemaVersion == null;
        JdbcTemplate jdbc = new JdbcTemplate(flyway.getConfiguration().getDataSource());
        EnvironmentCheck.verify(new JdbcMarkerStore(jdbc), declared, freshDatabase);
    }

    public AppEnvironment declared() {
        return declared;
    }
}
