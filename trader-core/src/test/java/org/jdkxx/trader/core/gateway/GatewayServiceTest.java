package org.jdkxx.trader.core.gateway;

import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.gateway.GatewayStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class GatewayServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void 账户号脱敏且未启用的网关拒绝手工连接() {
        StubGateway ibkr = new StubGateway(Broker.IBKR, GatewayStatus.disconnected("x"));
        StubGateway futu = new StubGateway(Broker.FUTU, GatewayStatus.disabled("x"));
        GatewayService service = new GatewayService(new GatewayRegistry(List.of(futu, ibkr)), (ObjectProvider) mock(ObjectProvider.class));

        assertThat(service.accounts(Broker.IBKR).join().get(0).maskedId()).isEqualTo("AB*****");
        assertThat(service.connect(Broker.IBKR).broker()).isEqualTo("IBKR");
        assertThat(ibkr.connects).isEqualTo(1);
        assertThatThrownBy(() -> service.connect(Broker.FUTU)).isInstanceOf(IllegalStateException.class);
        assertThat(service.events(10)).isEmpty();
        assertThat(service.lookup(Broker.FUTU, "AAPL")).isCompletedExceptionally();
    }
}
