package org.jdkxx.trader.core.system;

import org.jdkxx.trader.ai.AiProperties;
import org.jdkxx.trader.ai.OpenAiClientFactory;
import org.jdkxx.trader.core.gateway.GatewayRegistry;
import org.jdkxx.trader.core.gateway.GatewayViews;
import org.jdkxx.trader.core.gateway.StubGateway;
import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.gateway.GatewayStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SystemInfoServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void 汇总网关按券商顺序排列且存储未启用时给出占位() {
        ObjectProvider<Object> empty = mock(ObjectProvider.class);
        GatewayRegistry registry = new GatewayRegistry(List.of(
                new StubGateway(Broker.FUTU, GatewayStatus.disabled("x")),
                new StubGateway(Broker.IBKR, GatewayStatus.disconnected("y"))));
        SystemInfoService service = new SystemInfoService("trading-signal", "dev",
                (ObjectProvider) empty, registry, (ObjectProvider) empty,
                new OpenAiClientFactory(new AiProperties(null, "m", null, Duration.ofSeconds(1), 0)));

        SystemInfo info = service.current();

        assertThat(info.environment()).isEqualTo("DEV");
        assertThat(info.version()).isEqualTo("dev");
        assertThat(info.database().enabled()).isFalse();
        assertThat(info.gateways()).extracting(GatewayViews.GatewayView::broker).containsExactly("IBKR", "FUTU");
        assertThat(info.gateways().get(0).healthy()).isFalse();
        assertThat(info.ai().configured()).isFalse();
    }
}
