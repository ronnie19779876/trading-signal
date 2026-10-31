package org.jdkxx.trader.core.marketdata.universe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.jdkxx.trader.core.marketdata.MarketDataProperties;
import org.jdkxx.trader.core.marketdata.TestProperties;
import org.jdkxx.trader.domain.IndexCode;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.gateway.MarketDataGateway;
import org.jdkxx.trader.storage.marketdata.ConstituentRow;
import org.jdkxx.trader.storage.marketdata.IndexConstituentRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRepository;
import org.junit.jupiter.api.Test;

/**
 * The "exits per sync" guard of the constituent sync.
 *
 * <p>Why it is needed (found in the 2026-09-25 full-project review): {@code apply} is a pure set
 * difference, so whatever the source leaves out exits; and {@code WikipediaUniverseSource} only
 * errors when it parses fewer than 50 rows, while the S&amp;P 500 has 503: a page structure change
 * that leaves it parsing 60 rows still counts as "credible", and one sync marks 440+ members as
 * exited. Worse, it is invisible: the daily bar audit's denominator comes from the constituents,
 * drops along with them, and the completeness check stays green.
 *
 * <p>Threshold basis (production database records): SP500 has 503 members and 0 historical exits;
 * NDX100 has 101 members and 1 historical exit.
 */
class UniverseSyncGuardTest {

  private final InstrumentRepository instruments = mock(InstrumentRepository.class);
  private final IndexConstituentRepository constituents = mock(IndexConstituentRepository.class);
  private final MarketDataProperties props = TestProperties.defaults();

  private UniverseSyncService service() {
    when(instruments.upsert(
            any(Instrument.class),
            // use any(), not anyString(): the CSV rows' name is null and anyString() does not match
            // null,
            any()))
        // so all three would fall through to the mock's default return value 0 and collapse into
        // one (the first version of this test tripped on exactly that)
        .thenAnswer(inv -> (long) ((Instrument) inv.getArgument(0)).symbol().hashCode());
    return new UniverseSyncService(
        props,
        mock(UniverseSource.class),
        mock(SpyHoldingsCrossCheck.class),
        instruments,
        constituents,
        mock(MarketDataGateway.class));
  }

  /**
   * Current members: the symbol's hashCode is the id, consistent with the upsert double in
   * service().
   */
  private void existing(IndexCode index, List<String> symbols) {
    List<ConstituentRow> rows = new ArrayList<>();
    for (String s : symbols) {
      rows.add(
          new ConstituentRow(
              index, s.hashCode(), "IT", "Sub", "GICS", LocalDate.of(2026, 9, 3), null, "WIKI"));
    }
    when(constituents.current(index)).thenReturn(rows);
  }

  private static List<ConstituentEntry> entries(IndexCode index, List<String> symbols) {
    return symbols.stream().map(s -> new ConstituentEntry(index, s, s, "IT", "Sub", null)).toList();
  }

  private static List<String> symbols(String prefix, int n) {
    List<String> out = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      out.add(prefix + i);
    }
    return out;
  }

  @Test
  void 阈值取绝对值与百分比的较大者() {
    UniverseSyncService s = service();
    assertThat(s.removalLimit(503)).isEqualTo(25); // 503 × 5% = 25 > 5
    assertThat(s.removalLimit(101)).isEqualTo(5); // 101 × 5% = 5, equal to the absolute value
    assertThat(s.removalLimit(20)).isEqualTo(5); // 20 × 5% = 1 < 5, the absolute value wins
  }

  /**
   * The crux: when the source gives only 60 rows, 443 members must not be silently marked as
   * exited.
   */
  @Test
  void 来源大幅缩水时整个指数跳过_一条都不改() {
    UniverseSyncService s = service();
    existing(IndexCode.SP500, symbols("S", 503));

    UniverseSyncService.IndexResult r =
        s.apply(IndexCode.SP500, entries(IndexCode.SP500, symbols("S", 60)), "WIKI", false);

    assertThat(r.error()).isNotNull().contains("退出 443").contains("阈值 25").contains("force");
    assertThat(r.added()).isZero();
    assertThat(r.removed()).isZero();
    verify(constituents, never()).close(any(), anyLong(), any());
    verify(constituents, never()).add(any(), anyLong(), any(), any(), any(), any());
  }

  /**
   * No partial application when blocked: additions are skipped too, so no half-old, half-new state
   * is left behind.
   */
  @Test
  void 被挡下时新增也不应用() {
    UniverseSyncService s = service();
    existing(IndexCode.SP500, symbols("S", 503));
    List<String> fetched = new ArrayList<>(symbols("S", 60));
    fetched.add("NEWCO"); // the source contains one new instrument

    UniverseSyncService.IndexResult r =
        s.apply(IndexCode.SP500, entries(IndexCode.SP500, fetched), "WIKI", false);

    assertThat(r.error()).isNotNull();
    verify(constituents, never()).add(any(), anyLong(), any(), any(), any(), any());
  }

  @Test
  void force_放行大批退出() {
    UniverseSyncService s = service();
    existing(IndexCode.SP500, symbols("S", 503));

    UniverseSyncService.IndexResult r =
        s.apply(IndexCode.SP500, entries(IndexCode.SP500, symbols("S", 60)), "WIKI", true);

    assertThat(r.error()).isNull();
    assertThat(r.removed()).isEqualTo(443);
    verify(constituents, times(443)).close(eq(IndexCode.SP500), anyLong(), any());
  }

  /** A normal quarterly rebalance is unaffected: real exits per sync are 0~1. */
  @Test
  void 小幅变动照常应用() {
    UniverseSyncService s = service();
    existing(IndexCode.SP500, symbols("S", 503));
    List<String> fetched = new ArrayList<>(symbols("S", 503));
    fetched.remove(0);
    fetched.remove(0);
    fetched.add("NEWA");

    UniverseSyncService.IndexResult r =
        s.apply(IndexCode.SP500, entries(IndexCode.SP500, fetched), "WIKI", false);

    assertThat(r.error()).isNull();
    assertThat(r.removed()).isEqualTo(2);
    assertThat(r.added()).isEqualTo(1);
  }

  /**
   * On the first import the database is empty, there are 0 exits, and the guard must not block it.
   */
  @Test
  void 首次导入不受限() {
    UniverseSyncService s = service();
    existing(IndexCode.SP500, List.of());

    UniverseSyncService.IndexResult r =
        s.apply(IndexCode.SP500, entries(IndexCode.SP500, symbols("S", 503)), "WIKI", false);

    assertThat(r.error()).isNull();
    assertThat(r.added()).isEqualTo(503);
  }

  /**
   * The CSV fallback import goes through the same apply and the same guard: a three-line CSV must
   * not wipe out an entire index.
   */
  @Test
  void CSV导入同样受守护() {
    UniverseSyncService s = service();
    existing(IndexCode.SP500, symbols("S", 503));

    List<UniverseSyncService.IndexResult> out =
        s.importCsv("SP500,S0\nSP500,S1\nSP500,S2\n", false);

    assertThat(out)
        .singleElement()
        .satisfies(r -> assertThat(r.error()).isNotNull().contains("退出 500"));
    verify(constituents, never()).close(any(), anyLong(), any());
  }
}
