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
        return service(pauseDuringRefresh, true);
    }

    private QuoteSubscriptionService service(boolean pauseDuringRefresh, boolean autoSubscribe) {
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
        MarketDataProperties.Realtime props = new MarketDataProperties.Realtime(true, autoSubscribe, 10, pauseDuringRefresh, Duration.ofSeconds(1), Duration.ofSeconds(61));
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
        s.awaitReconcile();                                  // 连上后的对账跑在自己的线程上
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
        s.awaitReconcile();
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
    void 不自动订阅且没有订阅时_池变动不触发订阅() {
        // 开发实例：此前池变动直接对账，开发实例跟着订阅，与生产同时订
        QuoteSubscriptionService s = service(true, false);

        QuoteSubscriptionService.Result r = s.onPoolChanged();

        assertThat(subs).isEmpty();
        assertThat(r.error()).contains("auto-subscribe=false");
        assertThat(s.status().subscribed()).isZero();
    }

    @Test
    void 不自动订阅但手工对账过的_池变动照常跟随() {
        QuoteSubscriptionService s = service(true, false);
        s.reconcile();
        pool.set(List.of(row(1, "AAPL"), row(2, "MSFT"), row(3, "NVDA")));

        s.onPoolChanged();

        assertThat(s.status().subscribed()).isEqualTo(3);
    }

    @Test
    void 自动订阅的实例池变动照常对账() {
        QuoteSubscriptionService s = service(true, true);

        s.onPoolChanged();

        assertThat(s.status().subscribed()).isEqualTo(2);
    }

    @Test
    void 开发实例轮转前后都不订阅() {
        // 不自动订阅、也没手工对账过：2.0.2 前轮转结束无条件对账，开发实例每跑一次轮转就跟着订阅，与生产同占额度
        QuoteSubscriptionService s = service(true, false);

        s.beforeRefresh();
        s.afterRefresh();

        assertThat(subs).isEmpty();
        assertThat(s.paused()).as("轮转自己的暂停要解除，免得之后手工对账被挡").isFalse();
    }

    @Test
    void 不自动订阅但轮转前有订阅的_轮转后重新订阅() {
        QuoteSubscriptionService s = service(true, false);
        s.reconcile();
        now.set(now.get().plusSeconds(120));

        s.beforeRefresh();
        assertThat(s.status().subscribed()).isZero();
        s.afterRefresh();

        assertThat(s.status().subscribed()).isEqualTo(2);
    }

    @Test
    void 手工暂停的实例_轮转结束后保持暂停() {
        QuoteSubscriptionService s = service(true, true);
        s.reconcile();
        now.set(now.get().plusSeconds(120));
        s.pause();

        s.beforeRefresh();
        s.afterRefresh();

        assertThat(s.paused()).isTrue();
        assertThat(s.status().subscribed()).isZero();
        assertThat(subs).hasSize(1);
    }

    /**
     * 连上事件不能在监听线程上阻塞。
     *
     * <p>{@code GatewayListener} 的回调跑在富途的 {@code futu-scheduler} 上，而那个池只有 2 条线程，
     * 同时还要跑两条通道的心跳、建连超时、重连任务和两张 FutuReplyRegistry 的全部回复超时任务。
     * {@code reconcile()} 里是 {@code .get(30, SECONDS)} 的串行阻塞，一次对账能把一条线程占住几十秒，
     * 心跳排不上就会被判断线（2026-09-25 全项目审查发现）。
     */
    @Test
    void 连上事件立刻返回_对账阻塞也不占监听线程() throws Exception {
        java.util.concurrent.CountDownLatch blocked = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        // 先建服务再改桩：service(...) 内部会重新 stub subscribeQuotes，
        // 顺序反了会把下面这个阻塞桩覆盖掉，反证就不会变红（本测试第一版就是这么写的）
        QuoteSubscriptionService s = service(true);
        when(gateway.subscribeQuotes(anyList())).thenAnswer(inv -> {
            subs.add(List.copyOf(inv.getArgument(0)));
            blocked.countDown();
            return CompletableFuture.supplyAsync(() -> {
                try {
                    release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            });
        });

        long start = System.nanoTime();
        s.onConnected(Broker.FUTU, false);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(blocked.await(5, java.util.concurrent.TimeUnit.SECONDS)).as("对账确实在别的线程上跑起来了").isTrue();
        assertThat(elapsedMs).as("监听线程必须立刻返回，不能等对账（实际 %d ms）", elapsedMs).isLessThan(1000);

        release.countDown();
        s.awaitReconcile();
        assertThat(s.status().subscribed()).isEqualTo(2);
    }

    @Test
    void 盈透的连接事件不影响() {
        QuoteSubscriptionService s = service(true);
        s.onConnected(Broker.IBKR, false);
        assertThat(subs).isEmpty();
    }
}
