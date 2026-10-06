package org.jdkxx.trader.core.marketdata.bars;

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
import org.jdkxx.trader.gateway.UnknownSymbolException;
import org.jdkxx.trader.storage.marketdata.BarSyncStateRepository;
import org.jdkxx.trader.storage.marketdata.DailyBarRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

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

/**
 * 轮转的批次级恢复：一只改了名的代码不该毒掉整批。
 *
 * <p>2026-10-06 PSKY 在生产上就是这样一次丢掉 90 只标的的 K 线
 * （作业 #135：目标 521、成功 431、失败 90，失败的全是和它同批的）。
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

    @Test
    void 一只不认识的代码只丢自己_其余照常采集() throws Exception {
        givenGateway();
        // 富途对第一次订阅整批拒绝（点名 PSKY），剔除后重订成功
        when(gateway.subscribeDailyBars(anyList())).thenAnswer(inv -> {
            List<Instrument> asked = inv.getArgument(0);
            subscribed.add(symbols(asked));
            if (asked.stream().anyMatch(i -> "PSKY".equals(i.symbol()))) {
                return CompletableFuture.failedFuture(new UnknownSymbolException(Broker.FUTU, -1,
                        "sub KL_Day ×3 失败：未知股票 PSKY（retType=-1）", Set.of("PSKY")));
            }
            return CompletableFuture.completedFuture(null);
        });

        RotationRefresher.Result r = refresher().refresh(
                List.of(row(1, "AAPL"), row(2, "PSKY"), row(3, "MSFT")), x -> 5, "增量", new Ctx());

        // 第一次订了 3 只被拒，第二次只订剔除后的 2 只
        assertThat(subscribed).containsExactly(List.of("AAPL", "PSKY", "MSFT"), List.of("AAPL", "MSFT"));
        // 取 K 线只对剩下的两只，PSKY 一次都没取
        assertThat(fetched).containsExactly("AAPL", "MSFT");
        // 反订阅用的是同一份剔除后的名单——别把富途不认识的代码再送回去
        assertThat(unsubscribed).containsExactly(List.of("AAPL", "MSFT"));
        // 只有 PSKY 记为失败
        assertThat(r.ok()).isEqualTo(2);
        assertThat(r.failed()).isEqualTo(1);
        verify(instruments).markUnresolved(2L);
    }

    @Test
    void 剔除后仍然失败就退回原有行为_整批记失败() throws Exception {
        givenGateway();
        when(gateway.subscribeDailyBars(anyList())).thenAnswer(inv -> {
            subscribed.add(symbols(inv.getArgument(0)));
            return CompletableFuture.failedFuture(new UnknownSymbolException(Broker.FUTU, -1,
                    "sub KL_Day ×3 失败：未知股票 PSKY（retType=-1）", Set.of("PSKY")));
        });

        RotationRefresher.Result r = refresher().refresh(
                List.of(row(1, "AAPL"), row(2, "PSKY"), row(3, "MSFT")), x -> 5, "增量", new Ctx());

        assertThat(subscribed).hasSize(2);
        assertThat(fetched).isEmpty();
        assertThat(r.ok()).isZero();
        assertThat(r.failed()).isEqualTo(3);
    }

    /** 守护一：订阅超时不是"券商明确拒绝"，不该触发恢复，更不该核实或写库。 */
    @Test
    void 订阅超时不触发恢复() throws Exception {
        givenGateway();
        when(gateway.subscribeDailyBars(anyList())).thenAnswer(inv -> {
            subscribed.add(symbols(inv.getArgument(0)));
            return CompletableFuture.failedFuture(new java.util.concurrent.TimeoutException("超时"));
        });

        RotationRefresher.Result r = refresher().refresh(
                List.of(row(1, "AAPL"), row(2, "PSKY")), x -> 5, "增量", new Ctx());

        assertThat(subscribed).hasSize(1);
        assertThat(r.failed()).isEqualTo(2);
        verify(gateway, never()).staticInfo(anyList());
        verify(instruments, never()).markUnresolved(anyLong());
    }

    // ------------------------------------------------------------------ 替身

    private RotationRefresher refresher() {
        when(days.isTradingDay(any(), any())).thenReturn(true);
        when(days.between(any(), any(), any())).thenReturn(List.of(TODAY));
        SettledCutoff cutoff = new SettledCutoff(days, ZoneId.of("America/New_York"),
                Clock.fixed(TODAY.plusDays(1).atStartOfDay(ZoneId.of("America/New_York")).toInstant(),
                        ZoneId.of("America/New_York")));
        UnknownSymbolGuard guard = new UnknownSymbolGuard(gateway, instruments, 200, 3);
        return new RotationRefresher(TestProperties.defaults().refresh(), gateway, bars, states, cutoff, guard,
                d -> { });
    }

    private void givenGateway() {
        // staticInfo 混批免疫：对不认识的代码回一条 brokerId=0（2026-10-07 实测）
        when(gateway.staticInfo(anyList())).thenAnswer(inv -> {
            List<Instrument> asked = inv.getArgument(0);
            return CompletableFuture.completedFuture(asked.stream()
                    .map(i -> "PSKY".equals(i.symbol())
                            ? new InstrumentStatic(i, "未知股票", SecurityType.OTHER, 1, null, true, null, 0)
                            : new InstrumentStatic(i, i.symbol(), SecurityType.STOCK, 1, null, false, "5", 205189L))
                    .toList());
        });
        when(gateway.unsubscribeDailyBars(anyList())).thenAnswer(inv -> {
            unsubscribed.add(symbols(inv.getArgument(0)));
            return CompletableFuture.completedFuture(null);
        });
        when(gateway.recentDailyBars(any(), anyInt())).thenAnswer(inv -> {
            Instrument i = inv.getArgument(0);
            fetched.add(i.symbol());
            return CompletableFuture.completedFuture(List.of(new DailyBar(i, TODAY, BigDecimal.ONE, BigDecimal.ONE,
                    BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, 1L, BigDecimal.ONE, BigDecimal.ONE,
                    BigDecimal.ZERO, null, false)));
        });
        when(bars.upsertAll(anyLong(), anyList(), anyString())).thenReturn(1);
        when(instruments.find(Instrument.us("PSKY"))).thenReturn(Optional.of(row(2, "PSKY")));
    }

    private static InstrumentRow row(long id, String symbol) {
        return new InstrumentRow(id, Market.US, symbol, symbol, null, SecurityType.STOCK, 1, null, false, null,
                1L, "RESOLVED");
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
        public void progress(String text) {
        }

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
