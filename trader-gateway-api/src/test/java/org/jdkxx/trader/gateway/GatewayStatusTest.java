package org.jdkxx.trader.gateway;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayStatusTest {

    @Test
    void 未启用与已连接都视为健康() {
        assertThat(GatewayStatus.disabled("x").healthy()).isTrue();
        assertThat(GatewayStatus.connected("x").healthy()).isTrue();
        assertThat(GatewayStatus.disconnected("x").healthy()).isFalse();
        assertThat(GatewayStatus.error("x").healthy()).isFalse();
        assertThat(new GatewayStatus(GatewayState.RECONNECTING, "x", Instant.now()).healthy()).isFalse();
    }

    @Test
    void 空说明空时间空事实都有默认值且事实按键排序() {
        GatewayStatus s = new GatewayStatus(GatewayState.CONNECTING, null, null, null, null, 0, Map.of("b", "2", "a", "1"));
        assertThat(s.detail()).isEmpty();
        assertThat(s.checkedAt()).isNotNull();
        assertThat(s.facts().keySet()).containsExactly("a", "b");
        assertThat(new GatewayStatus(GatewayState.DISABLED, "x", Instant.now()).facts()).isEmpty();
    }
}
