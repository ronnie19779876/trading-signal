package org.jdkxx.trader.core.marketdata.quotes;

import org.jdkxx.trader.core.marketdata.MarketDataProperties;
import org.jdkxx.trader.core.marketdata.universe.UniverseScope;
import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.SecurityType;
import org.jdkxx.trader.domain.SubscriptionInfo;
import org.jdkxx.trader.gateway.MarketDataGateway;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QuoteSubscriptionServiceTest {

    private static InstrumentRow row(long id, String symbol) {
        return new InstrumentRow(id, Market.US, symbol, symbol, null, SecurityType.STOCK, 1, null, false, null, 1L, "RESOLVED");
    }

    private final List<List<Instrument>> subs = new ArrayList<>();
    private final List<List<Instrument>> unsubs = new ArrayList<>();
    private final List<Duration> sleeps = new ArrayList<>();
    private final AtomicReference<List<InstrumentRow>> pool = new AtomicReference<>(List.of(row(1, "AAPL"), row(2, "MSFT")));
    private final MarketDataGateway gateway = mock(MarketDataGateway.class);
    private final UniverseScope scope = mock(UniverseScope.class);
    private final QuoteCache cache = new QuoteCache();
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-03T10:00:00Z"));
    private final Clock clock = new Clock() {
        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };

    private QuoteSubscriptionService service(boolean pauseDuringRefresh) {
        when(scope.poolAndHoldings()).thenAnswer(inv -> pool.get());
        when(gateway.subscribeQuotes(anyList())).thenAnswer(inv -> {
            subs.add(List.copyOf(inv.getArgument(0)));
            return CompletableFuture.completedFuture(null);
        });
        when(gateway.unsubscribeQuotes(anyList())).thenAnswer(inv -> {
            unsubs.add(List.copyOf(inv.getArgument(0)));
            return CompletableFuture.completedFuture(null);
        });
        when(gateway.subscriptionInfo()).thenReturn(CompletableFuture.completedFuture(new SubscriptionInfo(2, 98, Map.of("Basic", 2), Instant.now())));
        MarketDataProperties.Realtime props = new MarketDataProperties.Realtime(true, true, 10, pauseDuringRefresh, Duration.ofSeconds(1), Duration.ofSeconds(61));
        return new QuoteSubscriptionService(props, gateway, scope, cache, clock, d -> {
            sleeps.add(d);
            now.set(now.get().plus(d));
        });
    }

    @Test
    void 对账新增订阅并在池变动后延后未满一分钟的反订阅() {
        QuoteSubscriptionService s = service(true);

        QuoteSubscriptionService.Result r1 = s.reconcile();
        assertThat(r1.added()).isEqualTo(2);
        assertThat(subs.get(0)).containsExactly(Instrument.us("AAPL"), Instrument.us("MSFT"));

        pool.set(List.of(row(1, "AAPL")));                  // MSFT 移出池，但订阅刚满 30 秒
        now.set(now.get().plusSeconds(30));
        QuoteSubscriptionService.Result r2 = s.reconcile();
        assertThat(r2.removed()).isZero();
        assertThat(r2.deferred()).isEqualTo(1);
        assertThat(unsubs).isEmpty();

        now.set(now.get().plusSeconds(40));                  // 满 70 秒 → 可以反订阅
        QuoteSubscriptionService.Result r3 = s.reconcile();
        assertThat(r3.removed()).isEqualTo(1);
        assertThat(unsubs.get(0)).containsExactly(Instrument.us("MSFT"));
        assertThat(s.status().subscribed()).isEqualTo(1);
        assertThat(s.status().quota().remainQuota()).isEqualTo(98);
    }

    @Test
    void 暂停释放全部订阅_恢复后重订_重连后清空并重订() {
        QuoteSubscriptionService s = service(true);
        s.onConnected(Broker.FUTU, false);                   // auto-subscribe
        assertThat(s.status().subscribed()).isEqualTo(2);

        // 手工暂停：订阅刚满 10 秒 → 未满 1 分钟，全部延后，不调券商
        now.set(now.get().plusSeconds(10));
        QuoteSubscriptionService.Result p = s.pause();
        assertThat(p.deferred()).isEqualTo(2);
        assertThat(unsubs).isEmpty();
        assertThat(s.paused()).isTrue();
        s.resume();

        // 轮转前暂停：等到满 1 分钟再反订阅（虚拟时钟由假 sleeper 推进）
        assertThat(s.beforeRefresh()).isZero();              // pause-during-refresh：返回 0 = 用配置批次
        assertThat(sleeps).hasSize(1);
        assertThat(s.paused()).isTrue();
        assertThat(s.status().subscribed()).isZero();
        assertThat(unsubs.get(0)).hasSize(2);
        assertThat(s.reconcile().error()).isEqualTo("已暂停");

        s.afterRefresh();
        assertThat(s.paused()).isFalse();
        assertThat(s.status().subscribed()).isEqualTo(2);

        s.onDisconnected(Broker.FUTU, "断线");
        assertThat(s.status().subscribed()).isZero();
        s.onConnected(Broker.FUTU, true);
        assertThat(s.status().subscribed()).isEqualTo(2);
        assertThat(subs).hasSize(3);
    }

    @Test
    void 不暂停时按剩余额度收缩批次() {
        QuoteSubscriptionService s = service(false);
        assertThat(s.beforeRefresh()).isEqualTo(88);         // 98 − 预留 10
        assertThat(s.paused()).isFalse();
    }

    @Test
    void 盈透的连接事件不影响() {
        QuoteSubscriptionService s = service(true);
        s.onConnected(Broker.IBKR, false);
        assertThat(subs).isEmpty();
    }
}
