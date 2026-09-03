package org.jdkxx.trader.app.integration;

import org.jdkxx.trader.domain.AccountRef;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.InstrumentInfo;
import org.jdkxx.trader.gateway.GatewayState;
import org.jdkxx.trader.gateway.ibkr.IbkrGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对真实 IB Gateway 的只读集成测试：连接 → 服务器时间 → 受管账户 → 合约查询 → 断开。
 */
@EnabledIfSystemProperty(named = "trader.integration", matches = "true")
class IbkrGatewayIT {

    @Test
    void 连接查询断开() throws Exception {
        String host = IntegrationEnv.env("TRADER_IBKR_HOST");
        int port = Integer.parseInt(IntegrationEnv.env("TRADER_IBKR_PORT"));
        int clientId = IntegrationEnv.envInt("TRADER_IBKR_TEST_CLIENT_ID", 91);

        try (IbkrGateway gateway = new IbkrGateway(IntegrationEnv.ibkr(host, port, clientId, Duration.ofSeconds(2)))) {
            gateway.connect().get(20, TimeUnit.SECONDS);
            assertThat(gateway.status().state()).isEqualTo(GatewayState.CONNECTED);
            assertThat(gateway.status().facts()).containsKey("serverVersion");
            System.out.println("[IT] ibkr facts=" + gateway.status().facts());

            Instant serverTime = gateway.serverTime().get(10, TimeUnit.SECONDS);
            assertThat(Duration.between(serverTime, Instant.now()).abs()).isLessThan(Duration.ofSeconds(60));

            List<AccountRef> accounts = gateway.accounts().get(10, TimeUnit.SECONDS);
            assertThat(accounts).isNotEmpty();
            System.out.println("[IT] ibkr accounts=" + accounts);

            List<InstrumentInfo> aapl = gateway.lookup(Instrument.us("AAPL")).get(15, TimeUnit.SECONDS);
            assertThat(aapl).isNotEmpty();
            InstrumentInfo info = aapl.get(0);
            assertThat(info.brokerRef()).isNotBlank();
            assertThat(info.minTick()).isNotNull();
            System.out.println("[IT] ibkr AAPL conId=" + info.brokerRef() + " name=" + info.name() + " minTick=" + info.minTick()
                    + " tz=" + info.timeZone() + " primary=" + info.primaryExchange() + " liquidHours=" + info.liquidHours());

            List<InstrumentInfo> none = gateway.lookup(Instrument.us("ZZZZNOSUCH")).get(15, TimeUnit.SECONDS);
            assertThat(none).isEmpty();

            gateway.disconnect();
            assertThat(gateway.status().state()).isEqualTo(GatewayState.DISCONNECTED);
        }
    }
}
