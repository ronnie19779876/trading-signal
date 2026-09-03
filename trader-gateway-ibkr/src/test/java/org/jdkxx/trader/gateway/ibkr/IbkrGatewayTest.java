package org.jdkxx.trader.gateway.ibkr;

import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.gateway.GatewayState;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IbkrGatewayTest {

    private static IbkrProperties props(boolean enabled, String host, Integer port, Integer clientId) {
        IbkrProperties d = IbkrProperties.disabled();
        return new IbkrProperties(enabled, true, host, port, clientId, null, "NASDAQ", d.connectTimeout(),
                d.requestTimeout(), d.heartbeatInterval(), d.heartbeatTimeout(), 40, d.reconnect());
    }

    @Test
    void 未启用时状态为DISABLED且不校验连接参数() {
        IbkrGateway gateway = new IbkrGateway(IbkrProperties.disabled());

        assertThat(gateway.broker()).isEqualTo(Broker.IBKR);
        assertThat(gateway.enabled()).isFalse();
        assertThat(gateway.status().state()).isEqualTo(GatewayState.DISABLED);
        assertThat(gateway.status().healthy()).isTrue();
        assertThat(gateway.connect()).isCompleted();
        assertThat(gateway.accounts()).isCompletedExceptionally();
    }

    @Test
    void 启用但缺少host时拒绝构建() {
        assertThatThrownBy(() -> new IbkrGateway(props(true, "", 1, 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("trader.ibkr.host");
    }

    @Test
    void 启用且配置齐全时初始为DISCONNECTED且说明不含主机() {
        try (IbkrGateway gateway = new IbkrGateway(props(true, "h", 1, 12))) {
            assertThat(gateway.status().state()).isEqualTo(GatewayState.DISCONNECTED);
            assertThat(gateway.status().detail()).doesNotContain("h:");
            assertThat(gateway.status().facts()).containsEntry("accounts", "0");
        }
    }
}
