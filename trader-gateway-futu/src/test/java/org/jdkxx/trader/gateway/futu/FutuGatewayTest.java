package org.jdkxx.trader.gateway.futu;

import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.gateway.GatewayState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FutuGatewayTest {

    private static FutuProperties props(boolean enabled, String host, Integer port) {
        FutuProperties d = FutuProperties.disabled();
        return new FutuProperties(enabled, true, host, port, "x", false, null, d.connectTimeout(), d.replyTimeout(),
                d.healthInterval(), d.reconnect(), Map.of("get-acc-list", "5/30s"));
    }

    @Test
    void 未启用时状态为DISABLED() {
        FutuGateway gateway = new FutuGateway(FutuProperties.disabled());

        assertThat(gateway.broker()).isEqualTo(Broker.FUTU);
        assertThat(gateway.status().state()).isEqualTo(GatewayState.DISABLED);
        assertThat(gateway.connect()).isCompleted();
    }

    @Test
    void 启用但缺少port时拒绝构建() {
        assertThatThrownBy(() -> new FutuGateway(props(true, "h", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("trader.futu.port");
    }

    @Test
    void 限频配置合并默认值且非法写法被拒绝() {
        FutuProperties p = props(true, "h", 1);
        assertThat(p.limit("get-acc-list").maxCalls()).isEqualTo(5);
        assertThat(p.limit("get-global-state").maxCalls()).isEqualTo(60);

        FutuProperties d = FutuProperties.disabled();
        FutuProperties bad = new FutuProperties(true, true, "h", 1, "x", false, null, d.connectTimeout(),
                d.replyTimeout(), d.healthInterval(), d.reconnect(), Map.of("get-acc-list", "five"));
        assertThatThrownBy(bad::validate).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 两条通道的状态合成() {
        assertThat(FutuGateway.combine(GatewayState.CONNECTED, GatewayState.CONNECTED)).isEqualTo(GatewayState.CONNECTED);
        assertThat(FutuGateway.combine(GatewayState.CONNECTED, GatewayState.RECONNECTING)).isEqualTo(GatewayState.RECONNECTING);
        assertThat(FutuGateway.combine(GatewayState.CONNECTING, GatewayState.DISCONNECTED)).isEqualTo(GatewayState.CONNECTING);
        assertThat(FutuGateway.combine(GatewayState.CONNECTED, GatewayState.ERROR)).isEqualTo(GatewayState.ERROR);
        assertThat(FutuGateway.combine(GatewayState.DISCONNECTED, GatewayState.DISCONNECTED)).isEqualTo(GatewayState.DISCONNECTED);
    }

    @Test
    void 启用时初始为DISCONNECTED() {
        try (FutuGateway gateway = new FutuGateway(props(true, "h", 1))) {
            assertThat(gateway.status().state()).isEqualTo(GatewayState.DISCONNECTED);
            assertThat(gateway.status().facts()).containsEntry("channel.qot", "DISCONNECTED");
            assertThat(gateway.status().detail()).doesNotContain("h:");
        }
    }
}
