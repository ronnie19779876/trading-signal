package org.jdkxx.trader.core.gateway;

import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.InstrumentInfo;
import org.jdkxx.trader.gateway.BrokerGateway;
import org.jdkxx.trader.gateway.ReferenceDataGateway;
import org.jdkxx.trader.storage.gateway.GatewayEventRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 网关的查询与控制门面：状态视图、手工连接/断开、账户（脱敏）、合约查询、事件时间线。
 */
@Service
public class GatewayService {

    private final GatewayRegistry registry;
    private final ObjectProvider<GatewayEventRepository> events;

    public GatewayService(GatewayRegistry registry, ObjectProvider<GatewayEventRepository> events) {
        this.registry = registry;
        this.events = events;
    }

    public List<GatewayViews.GatewayView> statuses() {
        return registry.all().stream().map(GatewayViews::of).toList();
    }

    public GatewayViews.GatewayView status(Broker broker) {
        return GatewayViews.of(registry.require(broker));
    }

    public GatewayViews.GatewayView connect(Broker broker) {
        BrokerGateway g = registry.require(broker);
        if (!g.enabled()) {
            throw new IllegalStateException(broker.displayName() + "网关未启用（trader." + broker.name().toLowerCase() + ".enabled=false）");
        }
        g.connect();
        return GatewayViews.of(g);
    }

    public GatewayViews.GatewayView disconnect(Broker broker) {
        BrokerGateway g = registry.require(broker);
        g.disconnect();
        return GatewayViews.of(g);
    }

    public CompletableFuture<List<GatewayViews.AccountView>> accounts(Broker broker) {
        return registry.require(broker).accounts()
                .thenApply(list -> list.stream().map(GatewayViews::of).toList());
    }

    public CompletableFuture<List<InstrumentInfo>> lookup(Broker broker, String symbol) {
        BrokerGateway g = registry.require(broker);
        if (!(g instanceof ReferenceDataGateway ref)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(broker.displayName() + "网关不提供合约查询"));
        }
        return ref.lookup(Instrument.us(symbol));
    }

    public List<GatewayViews.EventView> events(int limit) {
        GatewayEventRepository repo = events.getIfAvailable();
        if (repo == null) {
            return List.of();
        }
        return repo.latest(limit).stream().map(GatewayViews::of).toList();
    }
}
