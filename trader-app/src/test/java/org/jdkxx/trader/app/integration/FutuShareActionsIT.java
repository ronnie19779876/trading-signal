package org.jdkxx.trader.app.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.RehabFactor;
import org.jdkxx.trader.gateway.futu.FutuGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Read-only measurement against a real OpenD: whether share-count corporate actions (split /
 * reverse split / stock dividend) carry base/ert ratios, and how fwdA relates to those ratios in
 * mixed events (split + dividend, reverse split + spin-off). The conclusion determines how the
 * structural basis of signal evaluation is computed.
 */
@EnabledIfSystemProperty(named = "trader.integration", matches = "true")
class FutuShareActionsIT {

  @Test
  void 股数变动事件带比例() throws Exception {
    String host = IntegrationEnv.env("TRADER_FUTU_HOST");
    int port = Integer.parseInt(IntegrationEnv.env("TRADER_FUTU_PORT"));
    try (FutuGateway g = new FutuGateway(IntegrationEnv.futu(host, port, Duration.ofSeconds(2)))) {
      g.connect().get(20, TimeUnit.SECONDS);
      int shareEvents = 0;
      int withRatio = 0;
      for (String symbol :
          List.of("NVDA", "DD", "HON", "TRI", "GE", "AMCR", "GOOG", "MAR", "ASML", "TSM")) {
        List<RehabFactor> factors = g.rehab(Instrument.us(symbol)).get(20, TimeUnit.SECONDS);
        for (RehabFactor f : factors) {
          boolean share =
              f.has(RehabFactor.ACT_SPLIT)
                  || f.has(RehabFactor.ACT_JOIN)
                  || f.has(RehabFactor.ACT_BONUS)
                  || f.has(RehabFactor.ACT_TRANSFER);
          if (!share && !f.has(RehabFactor.ACT_SPIN_OFF)) {
            continue;
          }
          System.out.printf(
              "[IT] %s %s flag=%d fwdA=%s fwdB=%s split=%d:%d join=%d:%d bonus=%d:%d transfer=%d:%d%n",
              symbol,
              f.exDate(),
              f.companyActFlag(),
              f.fwdA().toPlainString(),
              f.fwdB().toPlainString(),
              f.splitBase(),
              f.splitErt(),
              f.joinBase(),
              f.joinErt(),
              f.bonusBase(),
              f.bonusErt(),
              f.transferBase(),
              f.transferErt());
          if (share) {
            shareEvents++;
            boolean ratio =
                (f.has(RehabFactor.ACT_SPLIT) && f.splitBase() > 0 && f.splitErt() > 0)
                    || (f.has(RehabFactor.ACT_JOIN) && f.joinBase() > 0 && f.joinErt() > 0)
                    || (f.has(RehabFactor.ACT_BONUS) && f.bonusBase() > 0 && f.bonusErt() > 0)
                    || (f.has(RehabFactor.ACT_TRANSFER)
                        && f.transferBase() > 0
                        && f.transferErt() > 0);
            withRatio += ratio ? 1 : 0;
          }
        }
      }
      System.out.println("[IT] shareEvents=" + shareEvents + " withRatio=" + withRatio);
      assertThat(shareEvents).isGreaterThan(0);
    }
  }
}
