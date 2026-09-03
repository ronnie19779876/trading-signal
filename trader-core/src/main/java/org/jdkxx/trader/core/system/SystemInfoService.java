package org.jdkxx.trader.core.system;

import org.jdkxx.trader.ai.OpenAiClientFactory;
import org.jdkxx.trader.core.gateway.GatewayRegistry;
import org.jdkxx.trader.core.gateway.GatewayViews;
import org.jdkxx.trader.storage.status.DatabaseStatus;
import org.jdkxx.trader.storage.status.DatabaseStatusService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 汇总版本、环境、数据库与各网关状态。存储未启用时 DatabaseStatusService 不存在，用 ObjectProvider 兜底。
 */
@Service
public class SystemInfoService {

    private final String application;
    private final String environment;
    private final ObjectProvider<BuildProperties> build;
    private final GatewayRegistry gateways;
    private final ObjectProvider<DatabaseStatusService> database;
    private final OpenAiClientFactory ai;

    public SystemInfoService(@Value("${spring.application.name:trading-signal}") String application,
                             @Value("${trader.environment:}") String environment,
                             ObjectProvider<BuildProperties> build,
                             GatewayRegistry gateways,
                             ObjectProvider<DatabaseStatusService> database,
                             OpenAiClientFactory ai) {
        this.application = application;
        this.environment = environment == null || environment.isBlank() ? "未声明" : environment.trim().toUpperCase();
        this.build = build;
        this.gateways = gateways;
        this.database = database;
        this.ai = ai;
    }

    public SystemInfo current() {
        BuildProperties b = build.getIfAvailable();
        DatabaseStatusService db = database.getIfAvailable();
        return new SystemInfo(
                application,
                b == null ? "dev" : b.getVersion(),
                b == null ? null : b.getTime(),
                environment,
                Instant.now(),
                db == null ? DatabaseStatus.disabled() : db.status(),
                gateways.all().stream().map(GatewayViews::of).toList(),
                ai.status());
    }
}
