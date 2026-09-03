package org.jdkxx.trader.core.gateway;

import org.jdkxx.trader.domain.AccountKind;
import org.jdkxx.trader.domain.AccountRef;
import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.gateway.BrokerGateway;
import org.jdkxx.trader.gateway.GatewayListener;
import org.jdkxx.trader.gateway.GatewayStatus;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** 测试用的网关桩。 */
public final class StubGateway implements BrokerGateway {

    private final Broker broker;
    private final GatewayStatus status;
    public int connects;
    public int disconnects;

    public StubGateway(Broker broker, GatewayStatus status) {
        this.broker = broker;
        this.status = status;
    }

    @Override
    public Broker broker() {
        return broker;
    }

    @Override
    public boolean enabled() {
        return status.state() != org.jdkxx.trader.gateway.GatewayState.DISABLED;
    }

    @Override
    public boolean autoConnect() {
        return true;
    }

    @Override
    public GatewayStatus status() {
        return status;
    }

    @Override
    public CompletableFuture<Void> connect() {
        connects++;
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void disconnect() {
        disconnects++;
    }

    @Override
    public CompletableFuture<List<AccountRef>> accounts() {
        return CompletableFuture.completedFuture(List.of(new AccountRef(broker, "AB123456", AccountKind.LIVE, Set.of(Market.US))));
    }

    @Override
    public void addListener(GatewayListener listener) {
    }
}
