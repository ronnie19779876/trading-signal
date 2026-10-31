package org.jdkxx.trader.app.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.futu.openapi.pb.GetGlobalState;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jdkxx.trader.domain.AccountRef;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.ValuationSnapshot;
import org.jdkxx.trader.gateway.GatewayState;
import org.jdkxx.trader.gateway.futu.FutuGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Read-only integration test against a real OpenD: connect both channels -&gt; global state -&gt;
 * account list -&gt; disconnect.
 */
@EnabledIfSystemProperty(named = "trader.integration", matches = "true")
class FutuGatewayIT {

  @Test
  void 连接查询断开() throws Exception {
    String host = IntegrationEnv.env("TRADER_FUTU_HOST");
    int port = Integer.parseInt(IntegrationEnv.env("TRADER_FUTU_PORT"));

    try (FutuGateway gateway =
        new FutuGateway(IntegrationEnv.futu(host, port, Duration.ofSeconds(2)))) {
      gateway.connect().get(20, TimeUnit.SECONDS);
      assertThat(gateway.status().state()).isEqualTo(GatewayState.CONNECTED);

      GetGlobalState.S2C state = gateway.globalState().get(10, TimeUnit.SECONDS);
      assertThat(state.getQotLogined()).isTrue();
      assertThat(state.getTrdLogined()).isTrue();
      System.out.println(
          "[IT] futu facts=" + gateway.status().facts() + " detail=" + gateway.status().detail());
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
   * Snapshot price and timestamp (used by the account snapshot as fallback valuation for positions
   * without a bar for the day). Read-only; it only prints and asserts nothing about semantics: it
   * measures whether lastPrice stays frozen at the last close after the close / on market holidays,
   * and which day asOf falls on.
   */
  @Test
  void 快照价与时间戳() throws Exception {
    String host = IntegrationEnv.env("TRADER_FUTU_HOST");
    int port = Integer.parseInt(IntegrationEnv.env("TRADER_FUTU_PORT"));
    ZoneId et = ZoneId.of("America/New_York");

    try (FutuGateway gateway =
        new FutuGateway(IntegrationEnv.futu(host, port, Duration.ofSeconds(2)))) {
      gateway.connect().get(20, TimeUnit.SECONDS);
      List<ValuationSnapshot> snaps =
          gateway
              .snapshots(List.of(Instrument.us("SPY"), Instrument.us("AAPL"), Instrument.us("BIL")))
              .get(20, TimeUnit.SECONDS);

      assertThat(snaps).hasSize(3);
      System.out.println("[IT] 此刻美东 " + ZonedDateTime.now(et));
      for (ValuationSnapshot s : snaps) {
        assertThat(s.lastPrice()).isNotNull().isPositive();
        System.out.println(
            "[IT] futu snapshot "
                + s.instrument().symbol()
                + " lastPrice="
                + s.lastPrice()
                + " asOf="
                + s.asOf()
                + " asOfET="
                + s.asOf().atZone(et)
                + " suspended="
                + s.suspended());
      }
      gateway.disconnect();
    }
  }
}
