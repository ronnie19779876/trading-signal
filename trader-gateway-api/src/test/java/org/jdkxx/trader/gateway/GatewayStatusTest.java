package org.jdkxx.trader.gateway;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayStatusTest {

    @Test
    void 未启用与已连接都视为健康() {
        assertThat(GatewayStatus.disabled("x").healthy()).isTrue();
        assertThat(GatewayStatus.connected("x").healthy()).isTrue();
        assertThat(GatewayStatus.disconnected("x").healthy()).isFalse();
        assertThat(GatewayStatus.error("x").healthy()).isFalse();
    }

    @Test
    void 空说明与空时间有默认值() {
        GatewayStatus s = new GatewayStatus(GatewayState.CONNECTING, null, null);
        assertThat(s.detail()).isEmpty();
        assertThat(s.checkedAt()).isNotNull();
    }
}
