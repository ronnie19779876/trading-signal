package org.jdkxx.trader.core.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jdkxx.trader.core.marketdata.bars.CalendarBackfillService;
import org.jdkxx.trader.core.marketdata.bars.DailyIncrementService;
import org.jdkxx.trader.core.marketdata.bars.DeepBackfillService;
import org.jdkxx.trader.core.marketdata.bars.RotationRefresher;
import org.jdkxx.trader.core.marketdata.jobs.JobService;
import org.jdkxx.trader.core.marketdata.universe.UniverseScope;
import org.jdkxx.trader.core.marketdata.universe.UniverseSyncService;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.SecurityType;
import org.jdkxx.trader.gateway.MarketDataGateway;
import org.jdkxx.trader.storage.marketdata.BarSyncStateRepository;
import org.jdkxx.trader.storage.marketdata.DailyBarRepository;
import org.jdkxx.trader.storage.marketdata.IndexConstituentRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;
import org.junit.jupiter.api.Test;

/**
 * The three numbers of the phantom-bar cleanup must share one basis.
 *
 * <p>Before 3.1.1 there were three bases (found in the 2026-09-25 full-project review): the audit
 * reported {@code phantomBars(20).size()} as the count, the cleanup dry run listed {@code
 * phantomBars(200).size()}, and the delete removed <b>everything</b> matching the condition. So
 * "the dry run shows you 200, clicking deletes thousands" was possible, and deleted bars only come
 * back through a backfill, which in turn brings the broker's dirty bars back. When the calendar
 * itself is broken the whole database is judged phantom, which is exactly when this path is most
 * dangerous.
 */
class PhantomCleanupTest {

  private final DailyBarRepository bars = mock(DailyBarRepository.class);
  private final InstrumentRepository instruments = mock(InstrumentRepository.class);

  private MarketDataFacade facade() {
    when(instruments.findById(anyLong()))
        .thenAnswer(
            inv ->
                Optional.of(
                    new InstrumentRow(
                        inv.getArgument(0),
                        Market.US,
                        "SPY",
                        "SPY",
                        null,
                        SecurityType.ETF,
                        1,
                        null,
                        false,
                        null,
                        1L,
                        "RESOLVED")));
    return new MarketDataFacade(
        TestProperties.defaults(),
        mock(JobService.class),
        mock(UniverseSyncService.class),
        mock(UniverseScope.class),
        mock(RotationRefresher.class),
        mock(DeepBackfillService.class),
        mock(DailyIncrementService.class),
        mock(CalendarBackfillService.class),
        mock(TradingDayRepository.class),
        instruments,
        mock(IndexConstituentRepository.class),
        bars,
        mock(BarSyncStateRepository.class),
        mock(MarketDataGateway.class),
        mock(InstrumentDirectory.class));
  }

  private static List<DailyBarRepository.PhantomBar> phantoms(int n) {
    List<DailyBarRepository.PhantomBar> out = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      out.add(
          new DailyBarRepository.PhantomBar(
              1L,
              LocalDate.of(2011, 7, 4).plusDays(i),
              BigDecimal.ONE,
              BigDecimal.ONE,
              BigDecimal.ONE,
              BigDecimal.ONE,
              0L,
              BigDecimal.ZERO));
    }
    return out;
  }

  /**
   * The crux: with thousands in total, one cleanup deletes only the 200 listed and never silently
   * deletes more.
   */
  @Test
  void 总数远大于批量时_只删列出来的那些() {
    List<DailyBarRepository.PhantomBar> listed = phantoms(DailyBarRepository.PHANTOM_BATCH);
    when(bars.phantomBarCount()).thenReturn(5000);
    when(bars.phantomBars(anyInt())).thenReturn(listed);
    when(bars.deletePhantomBars(listed)).thenReturn(listed.size());

    MarketDataFacade.PhantomCleanup r = facade().cleanupPhantomBars(true);

    assertThat(r.found()).as("总数照实报，不被批量上限截断").isEqualTo(5000);
    assertThat(r.listed()).isEqualTo(200);
    assertThat(r.deleted()).as("只删列出来的那些").isEqualTo(200);
    assertThat(r.remaining()).isEqualTo(4800);
    assertThat(r.bars()).hasSize(200);
    verify(bars).deletePhantomBars(listed);
  }

  @Test
  void 试跑不删任何东西() {
    when(bars.phantomBarCount()).thenReturn(3);
    when(bars.phantomBars(anyInt())).thenReturn(phantoms(3));

    MarketDataFacade.PhantomCleanup r = facade().cleanupPhantomBars(false);

    assertThat(r.applied()).isFalse();
    assertThat(r.found()).isEqualTo(3);
    assertThat(r.listed()).isEqualTo(3);
    assertThat(r.deleted()).isZero();
    assertThat(r.remaining()).as("一条没删，还剩全部").isEqualTo(3);
    verify(bars, never()).deletePhantomBars(org.mockito.ArgumentMatchers.anyList());
  }

  /**
   * The real situation (only 3 in production history): one round deletes them all and remaining
   * drops to zero.
   */
  @Test
  void 一轮删完时remaining归零() {
    List<DailyBarRepository.PhantomBar> listed = phantoms(3);
    when(bars.phantomBarCount()).thenReturn(3);
    when(bars.phantomBars(anyInt())).thenReturn(listed);
    when(bars.deletePhantomBars(listed)).thenReturn(3);

    MarketDataFacade.PhantomCleanup r = facade().cleanupPhantomBars(true);

    assertThat(r.deleted()).isEqualTo(3);
    assertThat(r.remaining()).isZero();
  }

  /** With no phantoms, no delete statement may be issued. */
  @Test
  void 没有幽灵时不发删除() {
    when(bars.phantomBarCount()).thenReturn(0);
    when(bars.phantomBars(anyInt())).thenReturn(List.of());

    MarketDataFacade.PhantomCleanup r = facade().cleanupPhantomBars(true);

    assertThat(r.found()).isZero();
    assertThat(r.deleted()).isZero();
    assertThat(r.bars()).isEmpty();
  }
}
