package org.jdkxx.trader.gateway.ibkr;

import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.gateway.GatewayState;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IbkrGatewayTest {

    @Test
    void 未启用时状态为DISABLED且不校验连接参数() {
        IbkrGateway gateway = new IbkrGateway(new IbkrProperties(false, null, null, null, null, Duration.ofSeconds(10)));

        assertThat(gateway.broker()).isEqualTo(Broker.IBKR);
        assertThat(gateway.status().state()).isEqualTo(GatewayState.DISABLED);
        assertThat(gateway.status().healthy()).isTrue();
    }

    @Test
    void 启用但缺少host时拒绝构建() {
        assertThatThrownBy(() -> new IbkrGateway(new IbkrProperties(true, "", 1, 1, null, Duration.ofSeconds(10))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("trader.ibkr.host");
    }

    @Test
    void 启用且配置齐全时为DISCONNECTED() {
        IbkrGateway gateway = new IbkrGateway(new IbkrProperties(true, "h", 1, 12, null, Duration.ofSeconds(10)));

        assertThat(gateway.status().state()).isEqualTo(GatewayState.DISCONNECTED);
        // 状态说明里不允许泄露主机等敏感值
        assertThat(gateway.status().detail()).doesNotContain("h:");
    }
}
