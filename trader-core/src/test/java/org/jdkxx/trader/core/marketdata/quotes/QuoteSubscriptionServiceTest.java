package org.jdkxx.trader.core.marketdata.quotes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.jdkxx.trader.core.marketdata.MarketDataProperties;
import org.jdkxx.trader.core.marketdata.universe.UniverseScope;
import org.jdkxx.trader.core.marketdata.universe.UnknownSymbolGuard;
import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.SecurityType;
import org.jdkxx.trader.domain.SubscriptionInfo;
import org.jdkxx.trader.gateway.MarketDataGateway;
import org.jdkxx.trader.storage.marketdata.InstrumentRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.junit.jupiter.api.Test;

class QuoteSubscriptionServiceTest {

  private static InstrumentRow row(long id, String symbol) {
    return new InstrumentRow(
        id,
        Market.US,
        symbol,
        symbol,
        null,
        SecurityType.STOCK,
        1,
        null,
        false,
        null,
        1L,
        "RESOLVED");
  }

  private final List<List<Instrument>> subs = new ArrayList<>();
  private final List<List<Instrument>> unsubs = new ArrayList<>();
  private final List<Duration> sleeps = new ArrayList<>();
  private final AtomicReference<List<InstrumentRow>> pool =
      new AtomicReference<>(List.of(row(1, "AAPL"), row(2, "MSFT")));
  private final MarketDataGateway gateway = mock(MarketDataGateway.class);
  private final UniverseScope scope = mock(UniverseScope.class);
  private final QuoteCache cache = new QuoteCache();
  private final AtomicReference<Instant> now =
      new AtomicReference<>(Instant.parse("2026-09-03T10:00:00Z"));
  private final Clock clock =
      new Clock() {
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
    when(gateway.subscribeQuotes(anyList()))
        .thenAnswer(
            inv -> {
              subs.add(List.copyOf(inv.getArgument(0)));
              return CompletableFuture.completedFuture(null);
            });
    when(gateway.unsubscribeQuotes(anyList()))
        .thenAnswer(
            inv -> {
              unsubs.add(List.copyOf(inv.getArgument(0)));
              return CompletableFuture.completedFuture(null);
            });
    when(gateway.subscriptionInfo())
        .thenReturn(
            CompletableFuture.completedFuture(
                new SubscriptionInfo(2, 98, Map.of("Basic", 2), Instant.now())));
    MarketDataProperties.Realtime props =
        new MarketDataProperties.Realtime(
            true,
            autoSubscribe,
            10,
            pauseDuringRefresh,
            Duration.ofSeconds(1),
            Duration.ofSeconds(61));
    return new QuoteSubscriptionService(
        props,
        gateway,
        scope,
        cache,
        new UnknownSymbolGuard(gateway, mock(InstrumentRepository.class), 200, 3),
        clock,
        d -> {
          sleeps.add(d);
          now.set(now.get().plus(d));
        });
  }

  /**
   * The trap introduced by marking UNRESOLVED: once marked, the instrument drops out of desired(),
   * so every reconciliation puts it into toRemove, while the broker does not know the symbol at all
   * and has no subscription to cancel; left in the local book it would make <b>every</b>
   * reconciliation fail and freeze real-time quotes. Since the broker does not know it, removing it
   * locally is the correct result.
   */
  @Test
  void 反订阅一只券商已不认识的代码时从本地账本摘掉而不是让对账永久失败() {
    QuoteSubscriptionService s = service(true);
    assertThat(s.reconcile().added()).isEqualTo(2);

    // MSFT was renamed: it disappears from the pool, and the broker no longer knows it either
    pool.set(List.of(row(1, "AAPL")));
    when(gateway.staticInfo(anyList()))
        .thenAnswer(
            inv -> {
              List<Instrument> asked = inv.getArgument(0);
              return CompletableFuture.completedFuture(
                  asked.stream()
                      .map(
                          i ->
                              "MSFT".equals(i.symbol())
                                  ? new org.jdkxx.trader.domain.InstrumentStatic(
                                      i, "未知股票", SecurityType.OTHER, 1, null, true, null, 0)
                                  : new org.jdkxx.trader.domain.InstrumentStatic(
                                      i,
                                      i.symbol(),
                                      SecurityType.STOCK,
                                      1,
                                      null,
                                      false,
                                      "5",
                                      205189L))
                      .toList());
            });
    when(gateway.unsubscribeQuotes(anyList()))
        .thenAnswer(
            inv -> {
              List<Instrument> asked = inv.getArgument(0);
              unsubs.add(List.copyOf(asked));
              if (asked.stream().anyMatch(i -> "MSFT".equals(i.symbol()))) {
                return CompletableFuture.failedFuture(
                    new org.jdkxx.trader.gateway.UnknownSymbolException(
                        Broker.FUTU,
                        -1,
                        "unsub 失败：未知股票 MSFT（retType=-1）",
                        java.util.Set.of("MSFT")));
              }
              return CompletableFuture.completedFuture(null);
            });

    now.set(
        now.get().plusSeconds(70)); // subscription is at least 1 minute old, may be unsubscribed
    QuoteSubscriptionService.Result r = s.reconcile();

    // The reconciliation did <b>not</b> fail, and MSFT has been removed from the local book
    assertThat(r.error()).isNull();
    assertThat(r.removed()).isEqualTo(1);
    assertThat(s.status().subscribed()).isEqualTo(1);

    // Reconcile again: it must not try to cancel a symbol the broker does not know
    unsubs.clear();
    QuoteSubscriptionService.Result again = s.reconcile();
    assertThat(again.error()).isNull();
    assertThat(unsubs).isEmpty();
  }

  @Test
  void 对账新增订阅并在池变动后延后未满一分钟的反订阅() {
    QuoteSubscriptionService s = service(true);

    QuoteSubscriptionService.Result r1 = s.reconcile();
    assertThat(r1.added()).isEqualTo(2);
    assertThat(subs.get(0)).containsExactly(Instrument.us("AAPL"), Instrument.us("MSFT"));

    pool.set(
        List.of(
            row(1, "AAPL"))); // MSFT leaves the pool, but its subscription is only 30 seconds old
    now.set(now.get().plusSeconds(30));
    QuoteSubscriptionService.Result r2 = s.reconcile();
    assertThat(r2.removed()).isZero();
    assertThat(r2.deferred()).isEqualTo(1);
    assertThat(unsubs).isEmpty();

    now.set(now.get().plusSeconds(40)); // 70 seconds old → may be unsubscribed
    QuoteSubscriptionService.Result r3 = s.reconcile();
    assertThat(r3.removed()).isEqualTo(1);
    assertThat(unsubs.get(0)).containsExactly(Instrument.us("MSFT"));
    assertThat(s.status().subscribed()).isEqualTo(1);
    assertThat(s.status().quota().remainQuota()).isEqualTo(98);
  }

  @Test
  void 暂停释放全部订阅_恢复后重订_重连后清空并重订() {
    QuoteSubscriptionService s = service(true);
    s.onConnected(Broker.FUTU, false); // auto-subscribe
    s.awaitReconcile(); // the reconciliation after connecting runs on its own thread
    assertThat(s.status().subscribed()).isEqualTo(2);

    // Manual pause: subscriptions are only 10 seconds old → under 1 minute, all deferred, no broker
    // call
    now.set(now.get().plusSeconds(10));
    QuoteSubscriptionService.Result p = s.pause();
    assertThat(p.deferred()).isEqualTo(2);
    assertThat(unsubs).isEmpty();
    assertThat(s.paused()).isTrue();
    s.resume();

    // Pause before a rotation: wait until 1 minute old, then unsubscribe (the fake sleeper advances
    // the virtual clock)
    assertThat(s.beforeRefresh())
        .isZero(); // pause-during-refresh: returning 0 = use the configured batch size
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
    assertThat(s.beforeRefresh()).isEqualTo(88); // 98 − reserve 10
    assertThat(s.paused()).isFalse();
  }

  @Test
  void 不自动订阅且没有订阅时_池变动不触发订阅() {
    // Dev instance: a pool change used to reconcile directly, so the dev instance subscribed along
    // with production
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

  /**
   * Before 3.5.2 any Futu reconnect reconciled, so a dev instance that had never subscribed picked
   * up the whole pool on the shared account quota after its tunnel blipped, and from then on
   * followed every pool change (found in the 2026-10-31 review).
   */
  @Test
  void 不自动订阅且从没订阅过的_重连后不订阅() {
    QuoteSubscriptionService s = service(true, false);
    s.onConnected(Broker.FUTU, false);
    s.awaitReconcile();
    s.onDisconnected(Broker.FUTU, "断线");
    s.onConnected(Broker.FUTU, true);
    s.awaitReconcile();

    assertThat(subs).isEmpty();
    assertThat(s.status().subscribed()).isZero();
    assertThat(s.onPoolChanged().error()).contains("auto-subscribe=false");
    assertThat(subs).isEmpty();
  }

  @Test
  void 不自动订阅但手工对账过的_重连后恢复订阅() {
    QuoteSubscriptionService s = service(true, false);
    s.reconcile();
    assertThat(s.status().subscribed()).isEqualTo(2);

    s.onDisconnected(Broker.FUTU, "断线");
    s.onConnected(Broker.FUTU, true);
    s.awaitReconcile();

    assertThat(s.status().subscribed()).isEqualTo(2);
    assertThat(subs).hasSize(2);
  }

  /**
   * The intent survives a reconnect whose own reconciliation failed: the next reconnect (and pool
   * changes in between) must still restore the subscriptions.
   */
  @Test
  void 不自动订阅但手工对账过的_重连对账失败后下次重连仍恢复() {
    QuoteSubscriptionService s = service(true, false);
    s.reconcile();
    s.onDisconnected(Broker.FUTU, "断线");
    when(gateway.subscribeQuotes(anyList()))
        .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("not connected")));
    s.onConnected(Broker.FUTU, true);
    s.awaitReconcile();
    assertThat(s.status().subscribed()).isZero();

    when(gateway.subscribeQuotes(anyList()))
        .thenAnswer(
            inv -> {
              subs.add(List.copyOf(inv.getArgument(0)));
              return CompletableFuture.completedFuture(null);
            });
    s.onDisconnected(Broker.FUTU, "断线");
    s.onConnected(Broker.FUTU, true);
    s.awaitReconcile();
    assertThat(s.status().subscribed()).isEqualTo(2);
  }

  @Test
  void 自动订阅的实例池变动照常对账() {
    QuoteSubscriptionService s = service(true, true);

    s.onPoolChanged();

    assertThat(s.status().subscribed()).isEqualTo(2);
  }

  @Test
  void 开发实例轮转前后都不订阅() {
    // No auto-subscribe and never reconciled manually: before 2.0.2 the end of a rotation
    // reconciled unconditionally, so the dev instance subscribed after every rotation, sharing
    // quota with production
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
   * The connect event must not block the listener thread.
   *
   * <p>{@code GatewayListener} callbacks run on Futu's {@code futu-scheduler}, a pool of only 2
   * threads, which also runs both channels' heartbeats, connect timeouts, reconnect tasks and every
   * reply-timeout task of the two FutuReplyRegistry instances. {@code reconcile()} blocks serially
   * on {@code .get(30, SECONDS)}, so one reconciliation could hold a thread for tens of seconds,
   * and a heartbeat that cannot get scheduled is judged a disconnect (found in the 2026-09-25
   * full-project review).
   */
  @Test
  void 连上事件立刻返回_对账阻塞也不占监听线程() throws Exception {
    java.util.concurrent.CountDownLatch blocked = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    // Build the service first, then change the stub: service(...) re-stubs subscribeQuotes
    // internally, and the reverse order would overwrite the blocking stub below, so the
    // counter-proof would never turn red (the first version of this test was written exactly that
    // way)
    QuoteSubscriptionService s = service(true);
    when(gateway.subscribeQuotes(anyList()))
        .thenAnswer(
            inv -> {
              subs.add(List.copyOf(inv.getArgument(0)));
              blocked.countDown();
              return CompletableFuture.supplyAsync(
                  () -> {
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

    assertThat(blocked.await(5, java.util.concurrent.TimeUnit.SECONDS))
        .as("对账确实在别的线程上跑起来了")
        .isTrue();
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
