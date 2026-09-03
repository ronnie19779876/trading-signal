package org.jdkxx.trader.core.system;

import org.jdkxx.trader.ai.OpenAiClientFactory;
import org.jdkxx.trader.gateway.BrokerGateway;
import org.jdkxx.trader.gateway.GatewayStatus;
import org.jdkxx.trader.storage.status.DatabaseStatus;
import org.jdkxx.trader.storage.status.DatabaseStatusService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * 汇总版本、环境、数据库与各网关状态。存储未启用时 DatabaseStatusService 不存在，用 ObjectProvider 兜底。
 */
@Service
public class SystemInfoService {

    private final String application;
    private final String environment;
    private final ObjectProvider<BuildProperties> build;
    private final List<BrokerGateway> gateways;
    private final ObjectProvider<DatabaseStatusService> database;
    private final OpenAiClientFactory ai;

    public SystemInfoService(@Value("${spring.application.name:trading-signal}") String application,
                             @Value("${trader.environment:}") String environment,
                             ObjectProvider<BuildProperties> build,
                             List<BrokerGateway> gateways,
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
        List<SystemInfo.GatewayView> views = gateways.stream()
                .sorted(Comparator.comparing(g -> g.broker().ordinal()))
                .map(g -> {
                    GatewayStatus s = g.status();
                    return new SystemInfo.GatewayView(g.broker().name(), g.broker().displayName(), g.broker().role(),
                            s.state().name(), s.healthy(), s.detail(), s.checkedAt());
                })
                .toList();
        return new SystemInfo(
                application,
                b == null ? "dev" : b.getVersion(),
                b == null ? null : b.getTime(),
                environment,
                Instant.now(),
                db == null ? DatabaseStatus.disabled() : db.status(),
                views,
                ai.status());
    }
}
