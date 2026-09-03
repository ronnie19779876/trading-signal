package org.jdkxx.trader.app.integration;

import com.futu.openapi.pb.GetGlobalState;
import org.jdkxx.trader.domain.AccountRef;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.gateway.GatewayState;
import org.jdkxx.trader.gateway.futu.FutuGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对真实 OpenD 的只读集成测试：连接两条通道 → 全局状态 → 账户列表 → 断开。
 */
@EnabledIfSystemProperty(named = "trader.integration", matches = "true")
class FutuGatewayIT {

    @Test
    void 连接查询断开() throws Exception {
        String host = IntegrationEnv.env("TRADER_FUTU_HOST");
        int port = Integer.parseInt(IntegrationEnv.env("TRADER_FUTU_PORT"));

        try (FutuGateway gateway = new FutuGateway(IntegrationEnv.futu(host, port, Duration.ofSeconds(2)))) {
            gateway.connect().get(20, TimeUnit.SECONDS);
            assertThat(gateway.status().state()).isEqualTo(GatewayState.CONNECTED);

            GetGlobalState.S2C state = gateway.globalState().get(10, TimeUnit.SECONDS);
            assertThat(state.getQotLogined()).isTrue();
            assertThat(state.getTrdLogined()).isTrue();
            System.out.println("[IT] futu facts=" + gateway.status().facts() + " detail=" + gateway.status().detail());
            assertThat(gateway.status().facts()).containsEntry("programStatus", "Ready");

            List<AccountRef> accounts = gateway.accounts().get(10, TimeUnit.SECONDS);
            assertThat(accounts).isNotEmpty();
            assertThat(accounts).anyMatch(a -> a.markets().contains(Market.US));
            System.out.println("[IT] futu accounts=" + accounts);

            gateway.disconnect();
            assertThat(gateway.status().state()).isEqualTo(GatewayState.DISCONNECTED);
        }
    }
}
