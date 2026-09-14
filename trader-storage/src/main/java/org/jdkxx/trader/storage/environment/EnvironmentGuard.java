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
 * 环境守卫：以 {@link FlywayMigrationStrategy} 的身份接管 Flyway 迁移——先校验环境标记，通过了才迁移。
 *
 * <p>这样做的好处：Spring Boot 让所有依赖 DataSource/JdbcTemplate 的 bean 都等待 Flyway 初始化完成，
 * 于是守卫必然先于任何业务代码触库；校验失败抛异常，应用启动即中止，而且<b>一条迁移都没执行</b>。
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
        Configuration c = flyway.getConfiguration();
        String schema = c.getDefaultSchema();
        String history = schema == null || schema.isBlank() ? c.getTable() : schema + "." + c.getTable();
        guard(new JdbcMarkerStore(new JdbcTemplate(c.getDataSource()), history), flyway::migrate);
    }

    /** 校验 → 迁移 → 空库盖章。顺序是这个守卫的全部意义，单独拆出来测。 */
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
