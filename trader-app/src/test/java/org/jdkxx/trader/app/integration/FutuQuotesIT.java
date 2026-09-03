package org.jdkxx.trader.app.integration;

import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.Quote;
import org.jdkxx.trader.domain.SubscriptionInfo;
import org.jdkxx.trader.gateway.futu.FutuGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对真实 OpenD：订阅 AAPL / TSLA 基础报价 → 60 秒内收到推送（首推即算）→ 额度已用 +2 → 断开释放。
 */
@EnabledIfSystemProperty(named = "trader.integration", matches = "true")
class FutuQuotesIT {

    @Test
    void 订阅推送与额度() throws Exception {
        String host = IntegrationEnv.env("TRADER_FUTU_HOST");
        int port = Integer.parseInt(IntegrationEnv.env("TRADER_FUTU_PORT"));
        try (FutuGateway g = new FutuGateway(IntegrationEnv.futu(host, port, Duration.ofSeconds(2)))) {
            g.connect().get(20, TimeUnit.SECONDS);
            List<Quote> received = new CopyOnWriteArrayList<>();
            g.addQuoteListener(received::add);
            SubscriptionInfo before = g.subscriptionInfo().get(15, TimeUnit.SECONDS);

            g.subscribeQuotes(List.of(Instrument.us("AAPL"), Instrument.us("TSLA"))).get(15, TimeUnit.SECONDS);
            long deadline = System.currentTimeMillis() + 60_000;
            while (received.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(500);
            }
            assertThat(received).isNotEmpty();
            Quote q = received.get(0);
            System.out.println("[IT] quotes=" + received.size() + " first=" + q.instrument() + " session=" + q.session() + " price=" + q.price()
                    + " rth=" + q.rthPrice() + " pre=" + q.preMarket() + " after=" + q.afterMarket() + " quoteTime=" + q.quoteTime());
            assertThat(q.price()).isPositive();

            SubscriptionInfo after = g.subscriptionInfo().get(15, TimeUnit.SECONDS);
            System.out.println("[IT] quota before=" + before.usedQuota() + " after=" + after.usedQuota() + " byType=" + after.byType());
            assertThat(after.usedQuota()).isEqualTo(before.usedQuota() + 2);
            assertThat(after.byType()).containsEntry("Basic", 2);
            g.disconnect();
        }
    }
}
