package org.jdkxx.trader.core.selection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jdkxx.trader.core.marketdata.jobs.JobContext;
import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.SecurityType;
import org.jdkxx.trader.storage.marketdata.DailyBarRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.jdkxx.trader.storage.marketdata.RehabFactorRepository;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;
import org.jdkxx.trader.storage.selection.SelectionScoreRepository;
import org.jdkxx.trader.storage.selection.SelectionSimRepository;
import org.jdkxx.trader.storage.selection.SimTradeRow;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SelectionSimServiceTest {

  private static final ZoneId ET = ZoneId.of("America/New_York");

  /** Score date (a Monday) */
  private static final LocalDate D = LocalDate.of(2026, 8, 3);

  private final SelectionSimRepository sim = mock(SelectionSimRepository.class);
  private final SelectionScoreRepository scores = mock(SelectionScoreRepository.class);
  private final InstrumentRepository instruments = mock(InstrumentRepository.class);
  private final DailyBarRepository bars = mock(DailyBarRepository.class);
  private final RehabFactorRepository rehabs = mock(RehabFactorRepository.class);
  private final TradingDayRepository days = mock(TradingDayRepository.class);
  private final List<String> partials = new ArrayList<>();
  private final JobContext ctx =
      new JobContext() {
        public long id() {
          return 1;
        }

        public void progress(String text) {}

        public void partial(String reason) {
          partials.add(reason);
        }

        public boolean cancelled() {
          return false;
        }
      };

  private SelectionSimService service(Instant now) {
    when(sim.account())
        .thenReturn(new SelectionSimRepository.Account("sim-v1", BigDecimal.valueOf(500_000), now));
    return new SelectionSimService(
        sim,
        scores,
        mock(SelectionTargets.class),
        instruments,
        bars,
        rehabs,
        days,
        () -> D.plusDays(30),
        new ObjectMapper().findAndRegisterModules(),
        Clock.fixed(now, ET),
        ET);
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
        "NASDAQ",
        id,
        "RESOLVED");
  }

  private static SelectionScoreRepository.ScoreView score(
      long id, String symbol, double composite) {
    return new SelectionScoreRepository.ScoreView(
        D,
        id,
        symbol,
        "Industrials",
        null,
        null,
        null,
        null,
        null,
        null,
        BigDecimal.valueOf(composite),
        null,
        "STRONG_BUY",
        1,
        "{}",
        "f",
        Instant.EPOCH);
  }

  private void top(int n) {
    List<SelectionScoreRepository.ScoreView> list = new ArrayList<>();
    for (int i = 1; i <= n; i++) {
      list.add(score(i, "S" + i, 100 - i));
    }
    when(scores.latestDate(any())).thenReturn(Optional.of(D));
    when(scores.day(any(), eq(D), any(), any())).thenReturn(list);
  }

  @Test
  void 不在前10名不能加() {
    top(12);
    SelectionSimService s = service(Instant.parse("2026-08-03T23:00:00Z"));
    assertThatThrownBy(() -> s.add("S11"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("前 10 名");
  }

  /**
   * Once ranks change, trades in progress may be names that have dropped out of the top 10: with
   * the pool full, top-10 names not held still cannot be added.
   */
  @Test
  void 池满不能加() {
    top(10);
    when(instruments.findById(3L)).thenReturn(Optional.of(row(3, "S3")));
    when(sim.activeFor(3L)).thenReturn(Optional.empty());
    when(sim.activeCount()).thenReturn(10);
    SelectionSimService s = service(Instant.parse("2026-08-03T23:00:00Z"));
    assertThatThrownBy(() -> s.add("S3"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("已满");
  }

  @Test
  void 开盘前点击当天开盘_开盘后点击下一个交易日() {
    LocalDate tue = D.plusDays(1);
    when(days.between(eq(Market.US), any(), any()))
        .thenAnswer(
            inv -> {
              LocalDate from = inv.getArgument(1);
              return List.of(D, tue, D.plusDays(2)).stream()
                  .filter(x -> !x.isBefore(from))
                  .toList();
            });
    SelectionSimService s = service(Instant.EPOCH);
    assertThat(s.nextOpenDate(Instant.parse("2026-08-03T13:29:00Z"))).isEqualTo(D); // 09:29 ET
    assertThat(s.nextOpenDate(Instant.parse("2026-08-03T13:30:00Z"))).isEqualTo(tue); // 09:30 ET
    assertThat(s.nextOpenDate(Instant.parse("2026-08-03T22:00:00Z"))).isEqualTo(tue);
  }

  @Test
  void 日历没覆盖未来时跳过周末() {
    when(days.between(eq(Market.US), any(), any())).thenReturn(List.of());
    SelectionSimService s = service(Instant.EPOCH);
    // Friday 18:00 ET -> next Monday
    assertThat(s.nextOpenDate(Instant.parse("2026-08-07T22:00:00Z")))
        .isEqualTo(LocalDate.of(2026, 8, 10));
  }

  private static SimTradeRow pending(long id, LocalDate plannedEntry, Instant requestedAt) {
    SimTradeRow p =
        SimTradeRow.pending(
            "sim-v1",
            id,
            "F1",
            D,
            (int) id,
            "STRONG_BUY",
            BigDecimal.TEN,
            requestedAt,
            BigDecimal.valueOf(100),
            BigDecimal.valueOf(2),
            BigDecimal.valueOf(96),
            BigDecimal.valueOf(96),
            "ATR",
            null,
            plannedEntry);
    return withId(p, id, "S" + id);
  }

  private static SimTradeRow withId(SimTradeRow t, long id, String symbol) {
    return new SimTradeRow(
        id,
        t.rulesVersion(),
        t.instrumentId(),
        symbol,
        t.modelVersion(),
        t.scoreDate(),
        t.scoreRank(),
        t.rating(),
        t.composite(),
        t.requestedAt(),
        t.setupClose(),
        t.setupAtr(),
        t.formulaStop(),
        t.stop(),
        t.stopLeg(),
        t.resistance(),
        t.plannedEntryDate(),
        t.status(),
        t.entryDate(),
        t.entryPrice(),
        t.shares(),
        t.plusOneR(),
        t.target(),
        t.targetSource(),
        t.touchedPlusOneR(),
        t.targetHitDate(),
        t.activeStop(),
        t.chandelierActive(),
        t.closeRequestedAt(),
        t.plannedExitDate(),
        t.closeKind(),
        t.closeNote(),
        t.exitDate(),
        t.exitPrice(),
        t.exitReason(),
        t.pnl(),
        t.returnPct(),
        t.rMultiple(),
        t.mfeR(),
        t.maeR(),
        t.barsHeld(),
        t.lastClose(),
        t.lastCloseDate(),
        t.unrealizedPnl(),
        t.spyReturn(),
        t.excessReturn(),
        t.shadowStatus(),
        t.shadowExitDate(),
        t.shadowExitPrice(),
        t.shadowExitReason(),
        t.shadowPnl(),
        t.shadowReturn(),
        t.shadowLastClose(),
        t.cancelReason(),
        t.updatedThrough(),
        t.updatedAt());
  }

  @Test
  void 入场开盘之前平仓等于撤销() {
    when(days.between(eq(Market.US), any(), any())).thenReturn(List.of());
    SimTradeRow p = pending(1, D.plusDays(1), Instant.parse("2026-08-03T22:00:00Z"));
    when(sim.find(1L)).thenReturn(Optional.of(p));
    SelectionSimService s =
        service(
            Instant.parse(
                // Monday 22:00 ET: entry is Tuesday's open, not reached yet
                "2026-08-04T02:00:00Z"));
    s.requestClose(1, null);
    ArgumentCaptor<SimTradeRow> c = ArgumentCaptor.forClass(SimTradeRow.class);
    verify(sim).update(c.capture());
    assertThat(c.getValue().status()).isEqualTo("CANCELLED");
    assertThat(c.getValue().closeRequestedAt()).isNull();
  }

  /**
   * Past the entry open but the job has not recorded it as held yet: it cannot be cancelled (that
   * open already "filled"), so a close request is recorded and it closes at the next open.
   */
  @Test
  void 过了入场开盘但还没记成持有_记平仓请求而不是撤销() {
    when(days.between(eq(Market.US), any(), any())).thenReturn(List.of());
    SimTradeRow p = pending(1, D.plusDays(1), Instant.parse("2026-08-03T22:00:00Z"));
    when(sim.find(1L)).thenReturn(Optional.of(p));
    SelectionSimService s = service(Instant.parse("2026-08-04T15:00:00Z")); // Tuesday 11:00 ET
    s.requestClose(1, "x");
    ArgumentCaptor<SimTradeRow> c = ArgumentCaptor.forClass(SimTradeRow.class);
    verify(sim).update(c.capture());
    assertThat(c.getValue().status()).isEqualTo("PENDING_ENTRY");
    assertThat(c.getValue().plannedExitDate()).isEqualTo(D.plusDays(2));
    assertThat(c.getValue().closeKind()).isEqualTo("MANUAL");
  }

  /** 40 flat bars (close 100) up to the score date, then two more: entry-day open 100. */
  private static List<DailyBar> flat(Instrument inst) {
    List<DailyBar> out = new ArrayList<>();
    for (int i = 40; i >= 0; i--) {
      out.add(bar(inst, D.minusDays(i), 100));
    }
    out.add(bar(inst, D.plusDays(1), 100));
    return out;
  }

  private static DailyBar bar(Instrument inst, LocalDate d, double c) {
    BigDecimal x = BigDecimal.valueOf(c);
    return new DailyBar(
        inst,
        d,
        x,
        x.add(BigDecimal.ONE),
        x.subtract(BigDecimal.ONE),
        x,
        x,
        1000,
        null,
        null,
        null,
        null,
        false);
  }

  /**
   * Two pending entries at the same open, cash for only one: the earlier request uses the cash
   * first (notional = the smaller of the equal-weight allotment and cash), the later one cannot
   * afford 1 share and is cancelled with the reason. One held trade already ties up 460k: 40k left,
   * less than the 50k equal-weight allotment.
   */
  @Test
  void 同日入场按请求先后分现金_不够的取消() {
    LocalDate entry = D.plusDays(1);
    SimTradeRow a = pending(1, entry, Instant.parse("2026-08-03T22:00:00Z"));
    SimTradeRow b = pending(2, entry, Instant.parse("2026-08-03T22:05:00Z"));
    when(sim.unfinished()).thenReturn(List.of(a, b));
    SimTradeRow held =
        withId(
            SimTradeRow.pending(
                "sim-v1",
                9,
                "F1",
                D.minusDays(30),
                1,
                "STRONG_BUY",
                BigDecimal.TEN,
                Instant.parse("2026-07-01T22:00:00Z"),
                BigDecimal.TEN,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                "ATR",
                null,
                D.minusDays(29)),
            9,
            "HELD");
    SimTradeRow heldOpen =
        new SimTradeRow(
            9,
            "sim-v1",
            9,
            "HELD",
            "F1",
            D.minusDays(30),
            1,
            "STRONG_BUY",
            BigDecimal.TEN,
            held.requestedAt(),
            BigDecimal.TEN,
            BigDecimal.ONE,
            BigDecimal.ONE,
            BigDecimal.ONE,
            "ATR",
            null,
            D.minusDays(29),
            "OPEN",
            D.minusDays(29),
            BigDecimal.valueOf(46),
            10_000,
            null,
            null,
            null,
            false,
            null,
            null,
            false,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            D.plusDays(5),
            null);
    when(sim.filled()).thenReturn(List.of(heldOpen));
    for (long id : new long[] {1, 2}) {
      InstrumentRow r = row(id, "S" + id);
      when(instruments.findById(id)).thenReturn(Optional.of(r));
      when(bars.find(eq(r.instrument()), eq(id), any(), any())).thenReturn(flat(r.instrument()));
    }
    when(rehabs.find(any(), anyLong())).thenReturn(List.of());
    when(instruments.find(any())).thenReturn(Optional.empty());
    when(sim.list(null)).thenReturn(List.of(a, b));
    when(sim.equity(any(), any())).thenReturn(List.of());
    when(days.between(eq(Market.US), any(), any()))
        .thenAnswer(
            inv -> {
              LocalDate from = inv.getArgument(1);
              LocalDate to = inv.getArgument(2);
              List<LocalDate> out = new ArrayList<>();
              for (LocalDate x = from; !x.isAfter(to); x = x.plusDays(1)) {
                out.add(x);
              }
              return out;
            });

    service(Instant.parse("2026-08-05T00:00:00Z")).update(entry, ctx);

    ArgumentCaptor<SimTradeRow> c = ArgumentCaptor.forClass(SimTradeRow.class);
    verify(sim, atLeastOnce()).update(c.capture());
    SimTradeRow first =
        c.getAllValues().stream().filter(x -> x.id() == 1).reduce((x, y) -> y).orElseThrow();
    SimTradeRow second =
        c.getAllValues().stream().filter(x -> x.id() == 2).reduce((x, y) -> y).orElseThrow();
    assertThat(first.status()).isEqualTo("OPEN");
    assertThat(first.shares()).isEqualTo(400); // cash 40k / open 100
    assertThat(second.status()).isEqualTo("CANCELLED");
    assertThat(second.cancelReason()).contains("可用现金");
    verify(sim).event(eq(2L), eq("CANCELLED"), any());
  }

  /**
   * The criteria use the latest close in the daily equity table (not the overview's
   * mark-to-market); without SPY data "beats SPY" is null, and under 12 months the verdict is
   * "observing".
   */
  @Test
  void 判据按逐日权益表最近一个收盘算() {
    LocalDate start = D.plusDays(1);
    when(sim.equity(start, D.plusDays(30)))
        .thenReturn(
            List.of(
                new SelectionSimRepository.EquityRow(
                    start, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.valueOf(500_000), 1),
                new SelectionSimRepository.EquityRow(
                    D.plusDays(29),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.valueOf(510_000),
                    1)));
    when(instruments.find(any())).thenReturn(Optional.empty());
    when(sim.list(null)).thenReturn(List.of());

    org.jdkxx.trader.domain.selection.sim.SimCriteria c =
        service(Instant.EPOCH).criteria(start, 500_000, D.plusDays(30));

    assertThat(c.asOf()).isEqualTo(D.plusDays(29));
    assertThat(c.days()).isEqualTo(28);
    assertThat(c.accountReturn()).isCloseTo(0.02, org.assertj.core.api.Assertions.within(1e-12));
    assertThat(c.annualized())
        .isCloseTo(Math.pow(1.02, 365.0 / 28) - 1, org.assertj.core.api.Assertions.within(1e-9));
    assertThat(c.beatSpy()).isNull();
    assertThat(c.verdict())
        .isEqualTo(org.jdkxx.trader.domain.selection.sim.SimCriteria.Verdict.OBSERVING);
  }

  @Test
  void 还没有入场时判据为未开始() {
    assertThat(service(Instant.EPOCH).criteria(null, 500_000, D).verdict())
        .isEqualTo(org.jdkxx.trader.domain.selection.sim.SimCriteria.Verdict.NOT_STARTED);
  }

  /**
   * Regression (2026-10-12 production false alarm): pending trades added after the 18:30 job with
   * an entry date of the next day have nothing to compute that day - the audit check simUpdated
   * must not fail, and the 22:30 catch-up must not rerun the whole daily job for them. Pending
   * trades whose entry date has arrived are still computed.
   */
  @Test
  void 入场日在审计日之后的待成交_不算没算到() {
    LocalDate d = D; // audit date
    SimTradeRow tomorrow = pending(1, d.plusDays(1), Instant.parse("2026-08-03T23:44:00Z"));
    when(sim.list(null)).thenReturn(List.of(tomorrow));
    when(sim.unfinished()).thenReturn(List.of(tomorrow));
    SelectionSimService s = service(Instant.EPOCH);

    org.jdkxx.trader.core.marketdata.audit.BarAuditService.Check c = s.auditChecks(d).getFirst();
    assertThat(c.name()).isEqualTo("simUpdated");
    assertThat(c.ok()).isTrue();
    assertThat(c.critical()).as("当天没有要算的，不是关键项").isFalse();
    assertThat(s.stale(d)).as("不该触发 22:30 补偿").isFalse();

    // entry date has arrived but never computed: still reported
    SimTradeRow today = pending(2, d, Instant.parse("2026-08-02T23:44:00Z"));
    when(sim.list(null)).thenReturn(List.of(tomorrow, today));
    when(sim.unfinished()).thenReturn(List.of(tomorrow, today));
    c = s.auditChecks(d).getFirst();
    assertThat(c.ok()).isFalse();
    assertThat(c.critical()).isTrue();
    assertThat(c.samples()).containsExactly("S2 #2 算到 （从未）");
    assertThat(c.detail()).contains("另有 1 笔待成交");
    assertThat(s.stale(d)).isTrue();
  }

  /** A copy of {@code t} that has been filled (and, when {@code exitDate} is set, closed). */
  private static SimTradeRow filled(
      SimTradeRow t,
      String status,
      LocalDate entryDate,
      int shares,
      LocalDate exitDate,
      BigDecimal exitPrice,
      LocalDate updatedThrough) {
    return new SimTradeRow(
        t.id(),
        t.rulesVersion(),
        t.instrumentId(),
        t.symbol(),
        t.modelVersion(),
        t.scoreDate(),
        t.scoreRank(),
        t.rating(),
        t.composite(),
        t.requestedAt(),
        t.setupClose(),
        t.setupAtr(),
        t.formulaStop(),
        t.stop(),
        t.stopLeg(),
        t.resistance(),
        t.plannedEntryDate(),
        status,
        entryDate,
        BigDecimal.valueOf(100),
        shares,
        t.plusOneR(),
        t.target(),
        t.targetSource(),
        t.touchedPlusOneR(),
        t.targetHitDate(),
        t.activeStop(),
        t.chandelierActive(),
        t.closeRequestedAt(),
        t.plannedExitDate(),
        t.closeKind(),
        t.closeNote(),
        exitDate,
        exitPrice,
        exitDate == null ? null : "STOP",
        t.pnl(),
        t.returnPct(),
        t.rMultiple(),
        t.mfeR(),
        t.maeR(),
        t.barsHeld(),
        t.lastClose(),
        t.lastCloseDate(),
        t.unrealizedPnl(),
        t.spyReturn(),
        t.excessReturn(),
        t.shadowStatus(),
        t.shadowExitDate(),
        t.shadowExitPrice(),
        t.shadowExitReason(),
        t.shadowPnl(),
        t.shadowReturn(),
        t.shadowLastClose(),
        t.cancelReason(),
        updatedThrough,
        t.updatedAt());
  }

  private void everyDayIsATradingDay() {
    when(days.between(eq(Market.US), any(), any()))
        .thenAnswer(
            inv -> {
              LocalDate from = inv.getArgument(1);
              LocalDate to = inv.getArgument(2);
              List<LocalDate> out = new ArrayList<>();
              for (LocalDate x = from; !x.isAfter(to); x = x.plusDays(1)) {
                out.add(x);
              }
              return out;
            });
  }

  /**
   * Regression (found in the 2026-10-31 review): when only a pending trade was recomputed, daily
   * equity was written from its entry date, skipping the days between the last equity row and that
   * entry (a missed or failed run) for good. Equity exists up to D, nothing is held, the pending
   * trade enters on D+2: D+1 must be filled in too.
   */
  @Test
  void 逐日权益从上次写到的次日补起_不因只有待成交重算而留缺口() {
    SimTradeRow closed =
        filled(
            pending(9, D.minusDays(29), Instant.parse("2026-07-01T22:00:00Z")),
            "CLOSED",
            D.minusDays(29),
            100,
            D,
            BigDecimal.valueOf(110),
            D);
    SimTradeRow p = pending(1, D.plusDays(2), Instant.parse("2026-08-04T22:00:00Z"));
    when(sim.unfinished()).thenReturn(List.of(p));
    when(sim.filled()).thenReturn(List.of(closed));
    InstrumentRow r = row(1, "S1");
    when(instruments.findById(1L)).thenReturn(Optional.of(r));
    List<DailyBar> series = new ArrayList<>(flat(r.instrument()));
    series.add(bar(r.instrument(), D.plusDays(2), 100));
    when(bars.find(eq(r.instrument()), eq(1L), any(), any())).thenReturn(series);
    when(rehabs.find(any(), anyLong())).thenReturn(List.of());
    when(instruments.find(any())).thenReturn(Optional.empty());
    when(sim.equity(any(), any()))
        .thenReturn(
            List.of(
                new SelectionSimRepository.EquityRow(
                    D,
                    BigDecimal.valueOf(501_000),
                    BigDecimal.ZERO,
                    BigDecimal.valueOf(501_000),
                    0)));
    everyDayIsATradingDay();

    service(Instant.parse("2026-08-06T00:00:00Z")).update(D.plusDays(2), ctx);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<SelectionSimRepository.EquityRow>> c = ArgumentCaptor.forClass(List.class);
    verify(sim).upsertEquity(c.capture());
    assertThat(c.getValue())
        .extracting(SelectionSimRepository.EquityRow::tradeDate)
        .containsExactly(D.plusDays(1), D.plusDays(2));
    assertThat(partials).isEmpty();
  }

  /**
   * Guard (found in the 2026-10-31 review): a trade already entered whose recompute comes back as
   * "not entered" (entry-day bar gone) or "would not enter" (entry open now at or below the stop)
   * means the bars were deleted or corrected. It used to hit a NullPointerException deep in the row
   * builder; now it fails with an explicit message and the row stays as it was.
   */
  @Test
  void 已入场的重算后变成未入场或不入场_明确报错且不改行() {
    // #1: entered on D+1, but there is no bar from D+1 on any more
    SimTradeRow gone =
        filled(
            pending(1, D.plusDays(1), Instant.parse("2026-08-03T22:00:00Z")),
            "OPEN",
            D.plusDays(1),
            100,
            null,
            null,
            D.plusDays(1));
    // #2: entered on D+1, but the corrected entry-day open is below the stop (96)
    SimTradeRow below =
        filled(
            pending(2, D.plusDays(1), Instant.parse("2026-08-03T22:05:00Z")),
            "OPEN",
            D.plusDays(1),
            100,
            null,
            null,
            D.plusDays(1));
    when(sim.unfinished()).thenReturn(List.of(gone, below));
    when(sim.filled()).thenReturn(List.of(gone, below));
    InstrumentRow r1 = row(1, "S1");
    InstrumentRow r2 = row(2, "S2");
    when(instruments.findById(1L)).thenReturn(Optional.of(r1));
    when(instruments.findById(2L)).thenReturn(Optional.of(r2));
    List<DailyBar> s1 = new ArrayList<>(flat(r1.instrument()));
    s1.removeIf(b -> b.tradeDate().isAfter(D));
    when(bars.find(eq(r1.instrument()), eq(1L), any(), any())).thenReturn(s1);
    List<DailyBar> s2 = new ArrayList<>(flat(r2.instrument()));
    s2.removeIf(b -> b.tradeDate().isAfter(D));
    s2.add(bar(r2.instrument(), D.plusDays(1), 90));
    when(bars.find(eq(r2.instrument()), eq(2L), any(), any())).thenReturn(s2);
    when(rehabs.find(any(), anyLong())).thenReturn(List.of());
    when(instruments.find(any())).thenReturn(Optional.empty());
    when(sim.equity(any(), any())).thenReturn(List.of());
    everyDayIsATradingDay();

    service(Instant.parse("2026-08-06T00:00:00Z")).update(D.plusDays(2), ctx);

    assertThat(partials).hasSize(2);
    assertThat(partials.get(0))
        .startsWith("#1 S1 重算失败：已于 2026-08-04 入场，重算后变成未入场：")
        .endsWith("（K 线被删或订正？需人工核对）");
    assertThat(partials.get(1))
        .startsWith("#2 S2 重算失败：已于 2026-08-04 入场，重算后变成不入场：开盘 90")
        .endsWith("（K 线被删或订正？需人工核对）");
    verify(sim, never()).update(any());
  }
}
