package org.jdkxx.trader.gateway.futu;

import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.gateway.GatewayState;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FutuGatewayTest {

    @Test
    void 未启用时状态为DISABLED() {
        FutuGateway gateway = new FutuGateway(new FutuProperties(false, null, null, "trading-signal", false, Duration.ofSeconds(10)));

        assertThat(gateway.broker()).isEqualTo(Broker.FUTU);
        assertThat(gateway.status().state()).isEqualTo(GatewayState.DISABLED);
    }

    @Test
    void 启用但缺少port时拒绝构建() {
        assertThatThrownBy(() -> new FutuGateway(new FutuProperties(true, "h", null, "x", false, Duration.ofSeconds(10))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("trader.futu.port");
    }
}
