package org.jdkxx.trader.core.marketdata.bars;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.jdkxx.trader.core.marketdata.TestProperties;
import org.jdkxx.trader.core.marketdata.jobs.JobContext;
import org.jdkxx.trader.core.marketdata.universe.UnknownSymbolGuard;
import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.InstrumentStatic;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.SecurityType;
import org.jdkxx.trader.gateway.MarketDataGateway;
import org.jdkxx.trader.gateway.RequestTimeoutException;
import org.jdkxx.trader.gateway.UnknownSymbolException;
import org.jdkxx.trader.storage.marketdata.BarSyncStateRepository;
import org.jdkxx.trader.storage.marketdata.DailyBarRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;
import org.junit.jupiter.api.Test;

/**
 * Batch-level recovery in the rotation: one renamed symbol must not poison the whole batch.
 *
 * <p>On 2026-10-06 PSKY did exactly that in production, losing the bars of 90 instruments in one go
 * (job #135: 521 targets, 431 succeeded, 90 failed, all of the failures in its batch).
 */
class RotationRecoveryTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 10, 6);

  private final MarketDataGateway gateway = mock(MarketDataGateway.class);
  private final DailyBarRepository bars = mock(DailyBarRepository.class);
  private final BarSyncStateRepository states = mock(BarSyncStateRepository.class);
  private final InstrumentRepository instruments = mock(InstrumentRepository.class);
  private final TradingDayRepository days = mock(TradingDayRepository.class);

  private final List<List<String>> subscribed = new ArrayList<>();
  private final List<List<String>> unsubscribed = new ArrayList<>();
  private final List<String> fetched = new ArrayList<>();

  /** Sleeps and unsubscriptions in the order they happened. */
  private final List<String> events = new ArrayList<>();

  @Test
  void 一只不认识的代码只丢自己_其余照常采集() throws Exception {
    givenGateway();
    // Futu rejects the first subscription as a whole (naming PSKY); after dropping it the
    // re-subscription succeeds
    when(gateway.subscribeDailyBars(anyList()))
        .thenAnswer(
            inv -> {
              List<Instrument> asked = inv.getArgument(0);
              subscribed.add(symbols(asked));
              if (asked.stream().anyMatch(i -> "PSKY".equals(i.symbol()))) {
                return CompletableFuture.failedFuture(
                    new UnknownSymbolException(
                        Broker.FUTU, -1, "sub KL_Day ×3 失败：未知股票 PSKY（retType=-1）", Set.of("PSKY")));
              }
              return CompletableFuture.completedFuture(null);
            });

    RotationRefresher.Result r =
        refresher()
            .refresh(
                List.of(row(1, "AAPL"), row(2, "PSKY"), row(3, "MSFT")), x -> 5, "增量", new Ctx());

    // The first subscription of 3 was rejected; the second only subscribes the 2 left after
    // dropping
    assertThat(subscribed)
        .containsExactly(List.of("AAPL", "PSKY", "MSFT"), List.of("AAPL", "MSFT"));
    // Bars are fetched only for the remaining two; PSKY is never fetched
    assertThat(fetched).containsExactly("AAPL", "MSFT");
    // The unsubscribe uses the same reduced list: do not send the symbol Futu does not know back to
    // it
    assertThat(unsubscribed).containsExactly(List.of("AAPL", "MSFT"));
    // Only PSKY counts as failed
    assertThat(r.ok()).isEqualTo(2);
    assertThat(r.failed()).isEqualTo(1);
    verify(instruments).markUnresolved(2L);
  }

  @Test
  void 剔除后仍然失败就退回原有行为_整批记失败() throws Exception {
    givenGateway();
    when(gateway.subscribeDailyBars(anyList()))
        .thenAnswer(
            inv -> {
              subscribed.add(symbols(inv.getArgument(0)));
              return CompletableFuture.failedFuture(
                  new UnknownSymbolException(
                      Broker.FUTU, -1, "sub KL_Day ×3 失败：未知股票 PSKY（retType=-1）", Set.of("PSKY")));
            });

    RotationRefresher.Result r =
        refresher()
            .refresh(
                List.of(row(1, "AAPL"), row(2, "PSKY"), row(3, "MSFT")), x -> 5, "增量", new Ctx());

    assertThat(subscribed).hasSize(2);
    assertThat(fetched).isEmpty();
    assertThat(r.ok()).isZero();
    assertThat(r.failed()).isEqualTo(3);
  }

  /**
   * Guard one: a subscription timeout is not "an explicit broker rejection"; it must not trigger
   * recovery, let alone verification or a database write.
   */
  @Test
  void 订阅超时不触发恢复() throws Exception {
    givenGateway();
    when(gateway.subscribeDailyBars(anyList()))
        .thenAnswer(
            inv -> {
              subscribed.add(symbols(inv.getArgument(0)));
              return CompletableFuture.failedFuture(
                  new java.util.concurrent.TimeoutException("超时"));
            });

    RotationRefresher.Result r =
        refresher().refresh(List.of(row(1, "AAPL"), row(2, "PSKY")), x -> 5, "增量", new Ctx());

    assertThat(subscribed).hasSize(1);
    assertThat(r.failed()).isEqualTo(2);
    verify(gateway, never()).staticInfo(anyList());
    verify(instruments, never()).markUnresolved(anyLong());
  }

  /**
   * Found in the 2026-10-31 review: a subscription that timed out may still have taken effect at
   * the broker, and that batch was never unsubscribed, so its quota stayed taken until the
   * connection closed and every following batch of the rotation could be rejected for quota. Now
   * the batch is not fetched, holdSeconds is waited out and the batch is unsubscribed (best effort)
   * before the next batch.
   */
  @Test
  void 订阅超时的批次_等满持有时间后反订阅_下一批照常() throws Exception {
    givenGateway();
    when(gateway.subscribeDailyBars(anyList()))
        .thenAnswer(
            inv -> {
              List<Instrument> asked = inv.getArgument(0);
              subscribed.add(symbols(asked));
              if (asked.contains(Instrument.us("AAPL"))) {
                return CompletableFuture.failedFuture(
                    new RequestTimeoutException(Broker.FUTU, "sub KL_Day"));
              }
              return CompletableFuture.completedFuture(null);
            });

    RotationRefresher.Result r =
        refresherWithBatchesOfTwo()
            .refresh(
                List.of(row(1, "AAPL"), row(2, "MSFT"), row(3, "NVDA"), row(4, "TSLA")),
                x -> 5,
                "增量",
                new Ctx());

    assertThat(subscribed).containsExactly(List.of("AAPL", "MSFT"), List.of("NVDA", "TSLA"));
    assertThat(fetched).as("超时的批次不取 K 线").containsExactly("NVDA", "TSLA");
    assertThat(events).containsExactly("sleep", "unsub AAPL,MSFT", "sleep", "unsub NVDA,TSLA");
    assertThat(r.ok()).isEqualTo(2);
    assertThat(r.failed()).isEqualTo(2);
    verify(gateway, never()).staticInfo(anyList());
  }

  @Test
  void 剔除未知代码后重订超时_同样等满持有时间后反订阅剩下的() throws Exception {
    givenGateway();
    when(gateway.subscribeDailyBars(anyList()))
        .thenAnswer(
            inv -> {
              List<Instrument> asked = inv.getArgument(0);
              subscribed.add(symbols(asked));
              if (asked.contains(Instrument.us("PSKY"))) {
                return CompletableFuture.failedFuture(
                    new UnknownSymbolException(
                        Broker.FUTU, -1, "sub KL_Day ×3 失败：未知股票 PSKY（retType=-1）", Set.of("PSKY")));
              }
              return CompletableFuture.failedFuture(
                  new RequestTimeoutException(Broker.FUTU, "sub KL_Day"));
            });

    RotationRefresher.Result r =
        refresher()
            .refresh(
                List.of(row(1, "AAPL"), row(2, "PSKY"), row(3, "MSFT")), x -> 5, "增量", new Ctx());

    assertThat(subscribed)
        .containsExactly(List.of("AAPL", "PSKY", "MSFT"), List.of("AAPL", "MSFT"));
    assertThat(fetched).isEmpty();
    assertThat(events).containsExactly("sleep", "unsub AAPL,MSFT");
    assertThat(r.failed()).isEqualTo(3);
  }

  // ------------------------------------------------------------------ test doubles

  private RotationRefresher refresher() {
    when(days.isTradingDay(any(), any())).thenReturn(true);
    when(days.between(any(), any(), any())).thenReturn(List.of(TODAY));
    SettledCutoff cutoff =
        new SettledCutoff(
            days,
            ZoneId.of("America/New_York"),
            Clock.fixed(
                TODAY.plusDays(1).atStartOfDay(ZoneId.of("America/New_York")).toInstant(),
                ZoneId.of("America/New_York")));
    UnknownSymbolGuard guard = new UnknownSymbolGuard(gateway, instruments, 200, 3);
    return new RotationRefresher(
        TestProperties.defaults().refresh(),
        gateway,
        bars,
        states,
        cutoff,
        guard,
        d -> events.add("sleep"));
  }

  /** Batches of two, via the quota coordinator's batch cap. */
  private RotationRefresher refresherWithBatchesOfTwo() {
    RotationRefresher r = refresher();
    r.coordinator(
        new RotationRefresher.QuotaCoordinator() {
          @Override
          public int beforeRefresh() {
            return 2;
          }
        });
    return r;
  }

  private void givenGateway() {
    // staticInfo is immune in a mixed batch: an unknown symbol gets a row with brokerId=0 (measured
    // 2026-10-07)
    when(gateway.staticInfo(anyList()))
        .thenAnswer(
            inv -> {
              List<Instrument> asked = inv.getArgument(0);
              return CompletableFuture.completedFuture(
                  asked.stream()
                      .map(
                          i ->
                              "PSKY".equals(i.symbol())
                                  ? new InstrumentStatic(
                                      i, "未知股票", SecurityType.OTHER, 1, null, true, null, 0)
                                  : new InstrumentStatic(
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
    when(gateway.unsubscribeDailyBars(anyList()))
        .thenAnswer(
            inv -> {
              unsubscribed.add(symbols(inv.getArgument(0)));
              events.add("unsub " + String.join(",", symbols(inv.getArgument(0))));
              return CompletableFuture.completedFuture(null);
            });
    when(gateway.recentDailyBars(any(), anyInt()))
        .thenAnswer(
            inv -> {
              Instrument i = inv.getArgument(0);
              fetched.add(i.symbol());
              return CompletableFuture.completedFuture(
                  List.of(
                      new DailyBar(
                          i,
                          TODAY,
                          BigDecimal.ONE,
                          BigDecimal.ONE,
                          BigDecimal.ONE,
                          BigDecimal.ONE,
                          BigDecimal.ONE,
                          1L,
                          BigDecimal.ONE,
                          BigDecimal.ONE,
                          BigDecimal.ZERO,
                          null,
                          false)));
            });
    when(bars.upsertAll(anyLong(), anyList(), anyString())).thenReturn(1);
    when(instruments.find(Instrument.us("PSKY"))).thenReturn(Optional.of(row(2, "PSKY")));
  }

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

  private static List<String> symbols(List<Instrument> list) {
    return list.stream().map(Instrument::symbol).toList();
  }

  private static final class Ctx implements JobContext {
    private final List<String> partials = new ArrayList<>();

    @Override
    public long id() {
      return 1;
    }

    @Override
    public void progress(String text) {}

    @Override
    public void partial(String reason) {
      partials.add(reason);
    }

    @Override
    public boolean cancelled() {
      return false;
    }
  }
}
