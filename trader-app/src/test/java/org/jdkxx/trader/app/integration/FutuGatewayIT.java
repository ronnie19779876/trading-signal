package org.jdkxx.trader.app.integration;

import com.futu.openapi.pb.GetGlobalState;
import org.jdkxx.trader.domain.AccountRef;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.ValuationSnapshot;
import org.jdkxx.trader.gateway.GatewayState;
import org.jdkxx.trader.gateway.futu.FutuGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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

    /**
     * 快照价与时间戳（账户快照给库里没有当日 K 线的持仓兜底估值时用）。只读，只打印不断言口径：
     * 用来实测收盘后/休市时 lastPrice 是不是冻结在上个收盘、asOf 落在哪一天。
     */
    @Test
    void 快照价与时间戳() throws Exception {
        String host = IntegrationEnv.env("TRADER_FUTU_HOST");
        int port = Integer.parseInt(IntegrationEnv.env("TRADER_FUTU_PORT"));
        ZoneId et = ZoneId.of("America/New_York");

        try (FutuGateway gateway = new FutuGateway(IntegrationEnv.futu(host, port, Duration.ofSeconds(2)))) {
            gateway.connect().get(20, TimeUnit.SECONDS);
            List<ValuationSnapshot> snaps = gateway.snapshots(
                    List.of(Instrument.us("SPY"), Instrument.us("AAPL"), Instrument.us("BIL"))).get(20, TimeUnit.SECONDS);

            assertThat(snaps).hasSize(3);
            System.out.println("[IT] 此刻美东 " + ZonedDateTime.now(et));
            for (ValuationSnapshot s : snaps) {
                assertThat(s.lastPrice()).isNotNull().isPositive();
                System.out.println("[IT] futu snapshot " + s.instrument().symbol() + " lastPrice=" + s.lastPrice()
                        + " asOf=" + s.asOf() + " asOfET=" + s.asOf().atZone(et) + " suspended=" + s.suspended());
            }
            gateway.disconnect();
        }
    }
}
