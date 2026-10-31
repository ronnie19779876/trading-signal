package org.jdkxx.trader.core.marketdata.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jdkxx.trader.core.marketdata.MarketDataProperties;
import org.jdkxx.trader.core.marketdata.TestProperties;
import org.jdkxx.trader.core.marketdata.universe.UniverseScope;
import org.jdkxx.trader.domain.IndexCode;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.SecurityType;
import org.jdkxx.trader.storage.marketdata.BarSyncStateRepository;
import org.jdkxx.trader.storage.marketdata.DailyBarRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.jdkxx.trader.storage.marketdata.JobRunRepository;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;
import org.junit.jupiter.api.Test;

class BarAuditServiceTest {

  private static final LocalDate LABOR_DAY = LocalDate.of(2026, 9, 7);

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

  /**
   * Stubs the denominator composition in one go: expected are the rows to collect, unusable the
   * rows filtered out.
   */
  private static void breakdown(
      UniverseScope scope, List<InstrumentRow> expected, List<InstrumentRow> unusable) {
    when(scope.targetBreakdown(any()))
        .thenReturn(
            new UniverseScope.TargetBreakdown(
                expected.size() + unusable.size(), 0, 0, unusable, expected));
  }

  private static MarketDataProperties props() {
    return TestProperties.defaults();
  }

  @Test
  void 休市日直接判通过_不去查K线() {
    TradingDayRepository days = mock(TradingDayRepository.class);
    when(days.covers(eq(Market.US), any())).thenReturn(true);
    when(days.isTradingDay(Market.US, LABOR_DAY)).thenReturn(false);
    DailyBarRepository bars = mock(DailyBarRepository.class);
    UniverseScope scope = mock(UniverseScope.class);

    BarAuditService s =
        new BarAuditService(
            props(),
            scope,
            bars,
            days,
            mock(BarSyncStateRepository.class),
            mock(JobRunRepository.class),
            null,
            Clock.systemUTC());
    BarAuditService.Report r = s.audit(LABOR_DAY);

    assertThat(r.ok()).isTrue();
    assertThat(r.date()).isEqualTo(LABOR_DAY);
    assertThat(r.summary()).containsEntry("tradingDay", false);
    assertThat(r.checks())
        .singleElement()
        .satisfies(
            c -> {
              assertThat(c.name()).isEqualTo("calendar");
              assertThat(c.detail()).contains("休市");
            });
    verifyNoInteractions(bars, scope);
  }

  @Test
  void 日历覆盖不到的日期照常审计() {
    TradingDayRepository days = mock(TradingDayRepository.class);
    when(days.covers(eq(Market.US), any())).thenReturn(false);
    DailyBarRepository bars = mock(DailyBarRepository.class);
    when(bars.instrumentIdsWithBarOn(any())).thenReturn(Set.of());
    when(bars.sanityOn(any())).thenReturn(new DailyBarRepository.DaySanity(0, 0, 0, 0, 0, 0));
    when(bars.continuityIssues(any(), any(), anyInt())).thenReturn(List.of());
    when(bars.coverageByInstrument()).thenReturn(List.of());
    when(bars.phantomBars(anyInt())).thenReturn(List.of());
    when(days.coverage(Market.US))
        .thenReturn(
            new TradingDayRepository.Coverage(
                LocalDate.of(2006, 8, 21), LocalDate.of(2026, 9, 18), 5000, 2591, 2409));
    UniverseScope scope = mock(UniverseScope.class);
    breakdown(scope, List.of(), List.of());
    BarSyncStateRepository states = mock(BarSyncStateRepository.class);
    when(states.findAll()).thenReturn(List.of());
    JobRunRepository jobs = mock(JobRunRepository.class);
    when(jobs.latestOf(any())).thenReturn(Optional.empty());

    BarAuditService s =
        new BarAuditService(props(), scope, bars, days, states, jobs, null, Clock.systemUTC());
    BarAuditService.Report r = s.audit(LocalDate.of(2010, 1, 4));

    assertThat(r.summary()).containsEntry("tradingDay", true);
    assertThat(r.checks())
        .extracting(BarAuditService.Check::name)
        .contains("completeness", "sanity", "continuity", "calendarCoverage");
  }

  /**
   * This change must hold exactly this: **the denominator's arithmetic is printed whether green or
   * not**. A silently shrinking denominator has bitten this project three times (constituents
   * wrongly removed, instruments downgraded, a trading halt on the day); each time numerator and
   * denominator dropped by one together and the check stayed green. With the arithmetic in plain
   * sight, shrinkage cannot hide.
   */
  @Test
  void 完整性检查全绿时也必须把分母算式打出来() {
    BarAuditService.Report r = auditWith(downgraded(100, 1));

    BarAuditService.Check c = check(r, "completeness");
    assertThat(c.ok()).isTrue();
    assertThat(c.detail())
        .as("绿的时候也要能看出分母是怎么来的")
        .contains("应采 99")
        .contains("名义 100")
        .contains("成分股 100")
        .contains("池与持仓另加 0")
        .contains("降级 1")
        .contains("查不到标的行 0")
        .contains("实采 99");
    assertThat(r.summary())
        .containsEntry("nominalTargets", 100)
        .containsEntry("downgradedTargets", 1)
        .containsEntry("targets", 99)
        .containsEntry("withBarOnDate", 99);
  }

  /**
   * Guard three: an instrument marked UNRESOLVED drops out of usable(), completeness loses one from
   * numerator and denominator together and stays green, and nobody sees anything; the same pitfall
   * as "wrongly removed constituents are invisible". So it must be reported separately by absolute
   * count.
   */
  @Test
  void 降级的标的必须被点名_哪怕完整性检查全绿() {
    UniverseScope scope = downgraded(100, 1);
    BarAuditService.Report r = auditWith(scope);

    BarAuditService.Check completeness = check(r, "completeness");
    assertThat(completeness.ok()).as("分母跟着变小，完整性检查照样全绿——正是这个坑").isTrue();

    BarAuditService.Check c = check(r, "resolveDowngrade");
    assertThat(c.critical()).isTrue();
    assertThat(c.ok()).as("1 只改名是正常的，不判失败").isTrue();
    assertThat(c.count()).isEqualTo(1);
    assertThat(c.detail()).contains("100 只目标里 1 只采不了").contains("DOWN0");
    assertThat(c.samples()).anySatisfy(s -> assertThat(s).contains("DOWN0").contains("UNRESOLVED"));
  }

  @Test
  void 降级得太多就判失败() {
    BarAuditService.Report r = auditWith(downgraded(100, 10));

    BarAuditService.Check c = check(r, "resolveDowngrade");
    assertThat(c.ok()).as("100 只里 10 只采不了，低于 95% 下限").isFalse();
    assertThat(c.count()).isEqualTo(10);
    assertThat(r.ok()).as("关键项，总判定跟着失败").isFalse();
  }

  private static List<InstrumentRow> expectedRows(int n) {
    List<InstrumentRow> out = new java.util.ArrayList<>();
    for (int i = 0; i < n; i++) {
      out.add(row(1 + i, i == 0 ? "AAPL" : "OK" + i));
    }
    return out;
  }

  /** {@code nominal} targets, {@code bad} of which are not RESOLVED. */
  private static UniverseScope downgraded(int nominal, int bad) {
    UniverseScope scope = mock(UniverseScope.class);
    List<InstrumentRow> unusable = new java.util.ArrayList<>();
    for (int i = 0; i < bad; i++) {
      unusable.add(
          new InstrumentRow(
              1000 + i,
              Market.US,
              "DOWN" + i,
              "DOWN" + i,
              null,
              SecurityType.STOCK,
              1,
              null,
              false,
              null,
              0L,
              "UNRESOLVED"));
    }
    when(scope.targetBreakdown(any()))
        .thenReturn(
            new UniverseScope.TargetBreakdown(
                nominal, 0, 0, unusable, expectedRows(nominal - bad)));
    return scope;
  }

  private static BarAuditService.Report auditWith(UniverseScope scope) {
    TradingDayRepository days = mock(TradingDayRepository.class);
    when(days.covers(eq(Market.US), any())).thenReturn(false);
    when(days.coverage(Market.US))
        .thenReturn(
            new TradingDayRepository.Coverage(
                LocalDate.of(2006, 8, 21), LocalDate.of(2026, 10, 16), 5071, 2539, 2532));
    DailyBarRepository bars = mock(DailyBarRepository.class);
    // Every expected target has a bar: this case wants "completeness fully green, yet the downgrade
    // is still named"
    Set<Long> withBar =
        scope.targetBreakdown(LocalDate.of(2026, 10, 6)).expected().stream()
            .map(InstrumentRow::id)
            .collect(java.util.stream.Collectors.toSet());
    when(bars.instrumentIdsWithBarOn(any())).thenReturn(withBar);
    when(bars.sanityOn(any())).thenReturn(new DailyBarRepository.DaySanity(0, 0, 0, 0, 0, 0));
    when(bars.continuityIssues(any(), any(), anyInt())).thenReturn(List.of());
    when(bars.gaps(any(), any(), anyInt())).thenReturn(List.of());
    when(bars.coverageByInstrument()).thenReturn(List.of());
    when(bars.phantomBarCount()).thenReturn(0);
    when(bars.phantomBars(anyInt())).thenReturn(List.of());
    BarSyncStateRepository states = mock(BarSyncStateRepository.class);
    when(states.findAll()).thenReturn(List.of());
    JobRunRepository jobs = mock(JobRunRepository.class);
    when(jobs.latestOf(any())).thenReturn(Optional.empty());
    return new BarAuditService(props(), scope, bars, days, states, jobs, null, Clock.systemUTC())
        .audit(LocalDate.of(2026, 10, 6));
  }

  @Test
  void 幽灵K线只提示不判失败() {
    // The broker has served dirty bars on US market holidays: the day is not in the calendar, but
    // the bar table has it
    TradingDayRepository days = mock(TradingDayRepository.class);
    when(days.covers(eq(Market.US), any())).thenReturn(false);
    when(days.coverage(Market.US))
        .thenReturn(
            new TradingDayRepository.Coverage(
                LocalDate.of(2006, 8, 21), LocalDate.of(2026, 9, 18), 5051, 2519, 2532));
    DailyBarRepository bars = mock(DailyBarRepository.class);
    when(bars.instrumentIdsWithBarOn(any())).thenReturn(Set.of(1L));
    when(bars.sanityOn(any())).thenReturn(new DailyBarRepository.DaySanity(1, 0, 0, 0, 0, 0));
    when(bars.continuityIssues(any(), any(), anyInt())).thenReturn(List.of());
    when(bars.gaps(any(), any(), anyInt())).thenReturn(List.of());
    when(bars.coverageByInstrument())
        .thenReturn(
            List.of(
                new DailyBarRepository.InstrumentCoverage(
                    1L, 5000, LocalDate.of(2006, 8, 21), LocalDate.of(2026, 9, 9))));
    when(bars.phantomBarCount()).thenReturn(1);
    when(bars.phantomBars(anyInt()))
        .thenReturn(
            List.of(
                new DailyBarRepository.PhantomBar(
                    1L,
                    LocalDate.of(2011, 7, 4),
                    new java.math.BigDecimal("128.24"),
                    new java.math.BigDecimal("129.30"),
                    new java.math.BigDecimal("109.57"),
                    new java.math.BigDecimal("109.57"),
                    285890054L,
                    java.math.BigDecimal.ZERO)));
    UniverseScope scope = mock(UniverseScope.class);
    breakdown(scope, List.of(row(1, "SPY")), List.of());
    BarSyncStateRepository states = mock(BarSyncStateRepository.class);
    when(states.findAll()).thenReturn(List.of());
    JobRunRepository jobs = mock(JobRunRepository.class);
    when(jobs.latestOf(any())).thenReturn(Optional.empty());

    BarAuditService.Report r =
        new BarAuditService(props(), scope, bars, days, states, jobs, null, Clock.systemUTC())
            .audit(LocalDate.of(2026, 9, 9));

    BarAuditService.Check c =
        r.checks().stream().filter(x -> x.name().equals("phantomBars")).findFirst().orElseThrow();
    assertThat(c.ok()).isFalse();
    assertThat(c.critical()).as("脏数据不影响当日正确性，提示即可").isFalse();
    assertThat(c.detail()).contains("交易日历之外");
    assertThat(c.samples()).singleElement().asString().contains("SPY").contains("2011-07-04");
    assertThat(r.ok()).as("关键项都过，总判定仍为通过").isTrue();
  }

  @Test
  void 收盘落定前写入的K线只提示不判失败() {
    TradingDayRepository days = mock(TradingDayRepository.class);
    when(days.covers(eq(Market.US), any())).thenReturn(false);
    when(days.coverage(Market.US))
        .thenReturn(
            new TradingDayRepository.Coverage(
                LocalDate.of(2006, 8, 21), LocalDate.of(2026, 9, 18), 5051, 2519, 2532));
    DailyBarRepository bars = mock(DailyBarRepository.class);
    when(bars.instrumentIdsWithBarOn(any())).thenReturn(Set.of(1L));
    when(bars.sanityOn(any())).thenReturn(new DailyBarRepository.DaySanity(1, 0, 0, 0, 0, 0));
    when(bars.continuityIssues(any(), any(), anyInt())).thenReturn(List.of());
    when(bars.coverageByInstrument())
        .thenReturn(
            List.of(
                new DailyBarRepository.InstrumentCoverage(
                    1L, 5000, LocalDate.of(2006, 8, 21), LocalDate.of(2026, 9, 9))));
    // The 09-09 bar written at 11:00 US/Eastern: left behind by an intraday deep backfill
    when(bars.unsettledBars(any(), any(), anyInt()))
        .thenReturn(
            List.of(
                new DailyBarRepository.UnsettledBar(
                    1L,
                    LocalDate.of(2026, 9, 9),
                    java.time.Instant.parse("2026-09-09T15:00:00Z"))));
    UniverseScope scope = mock(UniverseScope.class);
    breakdown(scope, List.of(row(1, "AAPL")), List.of());
    BarSyncStateRepository states = mock(BarSyncStateRepository.class);
    when(states.findAll()).thenReturn(List.of());
    JobRunRepository jobs = mock(JobRunRepository.class);
    when(jobs.latestOf(any())).thenReturn(Optional.empty());

    BarAuditService.Report r =
        new BarAuditService(props(), scope, bars, days, states, jobs, null, Clock.systemUTC())
            .audit(LocalDate.of(2026, 9, 9));

    BarAuditService.Check c =
        r.checks().stream().filter(x -> x.name().equals("unsettledBars")).findFirst().orElseThrow();
    assertThat(c.ok()).isFalse();
    assertThat(c.critical()).as("下一次增量会自动重拉覆盖").isFalse();
    assertThat(c.samples())
        .singleElement()
        .asString()
        .contains("AAPL")
        .contains("2026-09-09")
        .contains("11:00");
    assertThat(r.summary()).containsEntry("unsettledBars", 1);
    assertThat(r.ok()).isTrue();
  }

  @Test
  void 日历没覆盖到最早K线时给出提示但不判失败() {
    TradingDayRepository days = mock(TradingDayRepository.class);
    when(days.covers(eq(Market.US), any())).thenReturn(false);
    // The calendar only goes back to 2016 while the deep instruments' bars start in 2006: the
    // derived segment never ran
    when(days.coverage(Market.US))
        .thenReturn(
            new TradingDayRepository.Coverage(
                LocalDate.of(2016, 9, 12), LocalDate.of(2026, 9, 18), 2591, 2591, 0));
    DailyBarRepository bars = mock(DailyBarRepository.class);
    when(bars.instrumentIdsWithBarOn(any())).thenReturn(Set.of(1L));
    when(bars.sanityOn(any())).thenReturn(new DailyBarRepository.DaySanity(1, 0, 0, 0, 0, 0));
    when(bars.continuityIssues(any(), any(), anyInt())).thenReturn(List.of());
    when(bars.coverageByInstrument())
        .thenReturn(
            List.of(
                new DailyBarRepository.InstrumentCoverage(
                    1L, 5000, LocalDate.of(2006, 8, 21), LocalDate.of(2026, 9, 9))));
    when(bars.phantomBars(anyInt())).thenReturn(List.of());
    UniverseScope scope = mock(UniverseScope.class);
    breakdown(scope, List.of(row(1, "SPY")), List.of());
    BarSyncStateRepository states = mock(BarSyncStateRepository.class);
    when(states.findAll()).thenReturn(List.of());
    JobRunRepository jobs = mock(JobRunRepository.class);
    when(jobs.latestOf(any())).thenReturn(Optional.empty());

    BarAuditService.Report r =
        new BarAuditService(props(), scope, bars, days, states, jobs, null, Clock.systemUTC())
            .audit(LocalDate.of(2026, 9, 9));

    BarAuditService.Check c =
        r.checks().stream()
            .filter(x -> x.name().equals("calendarCoverage"))
            .findFirst()
            .orElseThrow();
    assertThat(c.ok()).isFalse();
    assertThat(c.critical()).as("日历不全不影响当日数据正确性").isFalse();
    assertThat(c.detail()).contains("2006-08-21").contains("日历回补");
  }

  /**
   * A shrunken constituent set must fail on its own. Why: it is completeness's denominator; when
   * constituents are wrongly removed the denominator shrinks with them and completeness stays
   * green, and that night's increment simply stops collecting those instruments (found in the
   * 2026-09-25 full-project review). The guard on the input side is covered by
   * UniverseSyncGuardTest.
   */
  @Test
  void 成分股数量正常时通过() {
    BarAuditService.Report r = auditWith(Map.of(IndexCode.SP500, 503, IndexCode.NDX100, 101));

    BarAuditService.Check c = check(r, "universeSize");
    assertThat(c.ok()).isTrue();
    assertThat(c.detail()).contains("503").contains("101");
  }

  @Test
  void 成分股集合缩水判失败_且是关键项() {
    // The case where a page structure change leaves the source parsing only 60 rows
    BarAuditService.Report r = auditWith(Map.of(IndexCode.SP500, 60, IndexCode.NDX100, 101));

    BarAuditService.Check c = check(r, "universeSize");
    assertThat(c.ok()).isFalse();
    assertThat(c.critical()).as("分母跟着缩水，别的检查发现不了").isTrue();
    assertThat(c.detail()).contains("SP500 只有 60 只").contains("下限 475").contains("UNIVERSE_SYNC");
    assertThat(c.samples()).hasSize(1);
    assertThat(r.ok()).isFalse();
  }

  private static BarAuditService.Check check(BarAuditService.Report r, String name) {
    return r.checks().stream().filter(x -> x.name().equals(name)).findFirst().orElseThrow();
  }

  /**
   * Everything except the constituent count is set up as "normal", so universeSize is the only
   * variable.
   */
  private static BarAuditService.Report auditWith(Map<IndexCode, Integer> counts) {
    TradingDayRepository days = mock(TradingDayRepository.class);
    when(days.covers(eq(Market.US), any())).thenReturn(false);
    when(days.coverage(Market.US))
        .thenReturn(
            new TradingDayRepository.Coverage(
                LocalDate.of(2006, 8, 21), LocalDate.of(2026, 9, 18), 5051, 2519, 2532));
    DailyBarRepository bars = mock(DailyBarRepository.class);
    when(bars.instrumentIdsWithBarOn(any())).thenReturn(Set.of(1L));
    when(bars.sanityOn(any())).thenReturn(new DailyBarRepository.DaySanity(1, 0, 0, 0, 0, 0));
    when(bars.continuityIssues(any(), any(), anyInt())).thenReturn(List.of());
    when(bars.coverageByInstrument())
        .thenReturn(
            List.of(
                new DailyBarRepository.InstrumentCoverage(
                    1L, 5000, LocalDate.of(2006, 8, 21), LocalDate.of(2026, 9, 9))));
    when(bars.phantomBars(anyInt())).thenReturn(List.of());
    UniverseScope scope = mock(UniverseScope.class);
    breakdown(scope, List.of(row(1, "AAPL")), List.of());
    when(scope.constituentCounts()).thenReturn(counts);
    BarSyncStateRepository states = mock(BarSyncStateRepository.class);
    when(states.findAll()).thenReturn(List.of());
    JobRunRepository jobs = mock(JobRunRepository.class);
    when(jobs.latestOf(any())).thenReturn(Optional.empty());

    return new BarAuditService(props(), scope, bars, days, states, jobs, null, Clock.systemUTC())
        .audit(LocalDate.of(2026, 9, 9));
  }

  /**
   * The phantom-bar count is the total, never the length of the sample list. Before 3.1.1 the audit
   * reported {@code phantomBars(20).size()}: even thousands showed as 20, while the cleanup deleted
   * everything matching; a decision made on "20 rows" deleted thousands (found in the 2026-09-25
   * full-project review).
   */
  @Test
  void 幽灵K线条数取总数_不被样本上限截断() {
    TradingDayRepository days = mock(TradingDayRepository.class);
    when(days.covers(eq(Market.US), any())).thenReturn(false);
    when(days.coverage(Market.US))
        .thenReturn(
            new TradingDayRepository.Coverage(
                LocalDate.of(2006, 8, 21), LocalDate.of(2026, 9, 18), 5051, 2519, 2532));
    DailyBarRepository bars = mock(DailyBarRepository.class);
    when(bars.instrumentIdsWithBarOn(any())).thenReturn(Set.of(1L));
    when(bars.sanityOn(any())).thenReturn(new DailyBarRepository.DaySanity(1, 0, 0, 0, 0, 0));
    when(bars.continuityIssues(any(), any(), anyInt())).thenReturn(List.of());
    when(bars.coverageByInstrument())
        .thenReturn(
            List.of(
                new DailyBarRepository.InstrumentCoverage(
                    1L, 5000, LocalDate.of(2006, 8, 21), LocalDate.of(2026, 9, 9))));
    // What a broken calendar looks like: thousands in total, only 20 in the sample list
    when(bars.phantomBarCount()).thenReturn(5000);
    List<DailyBarRepository.PhantomBar> samples = new java.util.ArrayList<>();
    for (int i = 0; i < 20; i++) {
      samples.add(
          new DailyBarRepository.PhantomBar(
              1L,
              LocalDate.of(2011, 7, 4).plusDays(i),
              java.math.BigDecimal.ONE,
              java.math.BigDecimal.ONE,
              java.math.BigDecimal.ONE,
              java.math.BigDecimal.ONE,
              0L,
              java.math.BigDecimal.ZERO));
    }
    when(bars.phantomBars(anyInt())).thenReturn(samples);
    UniverseScope scope = mock(UniverseScope.class);
    breakdown(scope, List.of(row(1, "SPY")), List.of());
    BarSyncStateRepository states = mock(BarSyncStateRepository.class);
    when(states.findAll()).thenReturn(List.of());
    JobRunRepository jobs = mock(JobRunRepository.class);
    when(jobs.latestOf(any())).thenReturn(Optional.empty());

    BarAuditService.Report r =
        new BarAuditService(props(), scope, bars, days, states, jobs, null, Clock.systemUTC())
            .audit(LocalDate.of(2026, 9, 9));

    BarAuditService.Check c = check(r, "phantomBars");
    assertThat(c.count()).isEqualTo(5000);
    assertThat(c.detail()).contains("5000 根").contains("一次最多订正 200 条");
    assertThat(c.samples()).as("样本仍然封顶").hasSize(20);
    assertThat(r.summary()).containsEntry("phantomBars", 5000);
  }

  /**
   * With three critical checks failing at once, the overall verdict must be false.
   *
   * <p>Before 3.1.1 this path had <b>zero coverage</b>: across 5 tests completeness / sanity /
   * continuity only ever took the passing branch, {@code r.ok()} was only ever asserted isTrue(),
   * i.e. the path behind section one of check-daily.sh reporting failure never ran once (found in
   * the 2026-09-25 full-project review).
   */
  @Test
  void 关键项失败时总判定为false() {
    TradingDayRepository days = mock(TradingDayRepository.class);
    when(days.covers(eq(Market.US), any())).thenReturn(false);
    when(days.coverage(Market.US))
        .thenReturn(
            new TradingDayRepository.Coverage(
                LocalDate.of(2006, 8, 21), LocalDate.of(2026, 9, 18), 5051, 2519, 2532));
    DailyBarRepository bars = mock(DailyBarRepository.class);
    when(bars.instrumentIdsWithBarOn(any()))
        .thenReturn(Set.of()); // every target is missing its bar
    when(bars.sanityOn(any()))
        .thenReturn(
            new DailyBarRepository.DaySanity(
                2,
                1,
                1,
                0,
                0,
                // bars, OHLC inconsistent, non-positive close, blank, zero volume, turnover missing
                1));
    when(bars.continuityIssues(any(), any(), anyInt()))
        .thenReturn(
            List.of(
                new DailyBarRepository.ContinuityIssue(
                    1L,
                    LocalDate.of(2026, 9, 9),
                    new java.math.BigDecimal("100"),
                    new java.math.BigDecimal("101"))));
    when(bars.coverageByInstrument())
        .thenReturn(
            List.of(
                new DailyBarRepository.InstrumentCoverage(
                    1L, 5000, LocalDate.of(2006, 8, 21), LocalDate.of(2026, 9, 9))));
    when(bars.phantomBarCount()).thenReturn(0);
    when(bars.phantomBars(anyInt())).thenReturn(List.of());
    UniverseScope scope = mock(UniverseScope.class);
    breakdown(scope, List.of(row(1, "AAPL"), row(2, "MSFT")), List.of());
    BarSyncStateRepository states = mock(BarSyncStateRepository.class);
    when(states.findAll()).thenReturn(List.of());
    JobRunRepository jobs = mock(JobRunRepository.class);
    when(jobs.latestOf(any())).thenReturn(Optional.empty());

    BarAuditService.Report r =
        new BarAuditService(props(), scope, bars, days, states, jobs, null, Clock.systemUTC())
            .audit(LocalDate.of(2026, 9, 9));

    assertThat(r.ok()).as("关键项有失败，总判定必须是 false").isFalse();
    assertThat(check(r, "completeness").ok()).isFalse();
    assertThat(check(r, "completeness").count()).isEqualTo(2);
    assertThat(check(r, "completeness").samples()).containsExactly("AAPL", "MSFT");
    assertThat(check(r, "sanity").ok()).isFalse();
    assertThat(check(r, "sanity").count()).as("OHLC 不一致 + 非正收盘 + 成交额为空").isEqualTo(3);
    assertThat(check(r, "continuity").ok()).isFalse();
    assertThat(check(r, "continuity").samples())
        .singleElement()
        .asString()
        .contains("AAPL")
        .contains("2026-09-09");
    assertThat(r.checks())
        .filteredOn(BarAuditService.Check::critical)
        .allSatisfy(c -> assertThat(c.critical()).isTrue());
  }

  /**
   * Regression (production false alarm on 2026-10-12): the Saturday 10-10 constituent sync added
   * SKYD and others and removed CTVA; 10-09 was audited before Monday's close. Uses the real
   * UniverseScope: the denominator is the constituents valid on 10-09 (validOn); the new ones were
   * not 10-09 targets and must not count as missing; CTVA, which left, was still a constituent that
   * day, so its missing bar must still be reported.
   */
  @Test
  void 完整性按审计日当天的名单_事后新进的不算缺_事后退出的仍要采() {
    LocalDate d = LocalDate.of(2026, 10, 9);
    org.jdkxx.trader.storage.marketdata.IndexConstituentRepository constituents =
        mock(org.jdkxx.trader.storage.marketdata.IndexConstituentRepository.class);
    org.jdkxx.trader.storage.marketdata.PoolRepository pool =
        mock(org.jdkxx.trader.storage.marketdata.PoolRepository.class);
    org.jdkxx.trader.storage.marketdata.InstrumentRepository instruments =
        mock(org.jdkxx.trader.storage.marketdata.InstrumentRepository.class);
    org.jdkxx.trader.storage.marketdata.ConstituentRow aapl = member(1, null);
    org.jdkxx.trader.storage.marketdata.ConstituentRow ctva = member(2, LocalDate.of(2026, 10, 10));
    org.jdkxx.trader.storage.marketdata.ConstituentRow skyd = member(3, null);
    when(constituents.validOn(d)).thenReturn(List.of(aapl, ctva));
    when(constituents.currentAll()).thenReturn(List.of(aapl, skyd));
    when(pool.findAll()).thenReturn(List.of());
    when(instruments.findByIds(any()))
        .thenAnswer(
            inv -> {
              java.util.Collection<Long> ids = inv.getArgument(0);
              return ids.stream()
                  .map(id -> row(id, id == 1 ? "AAPL" : id == 2 ? "CTVA" : "SKYD"))
                  .toList();
            });
    UniverseScope scope = new UniverseScope(constituents, pool, instruments);

    TradingDayRepository days = mock(TradingDayRepository.class);
    when(days.covers(eq(Market.US), any())).thenReturn(false);
    when(days.coverage(Market.US))
        .thenReturn(
            new TradingDayRepository.Coverage(
                LocalDate.of(2006, 8, 21), LocalDate.of(2026, 10, 16), 5071, 2539, 2532));
    DailyBarRepository bars = mock(DailyBarRepository.class);
    when(bars.instrumentIdsWithBarOn(d))
        .thenReturn(
            Set.of(1L)); // AAPL has one, CTVA is missing, SKYD was not a constituent yet that day
    when(bars.sanityOn(any())).thenReturn(new DailyBarRepository.DaySanity(0, 0, 0, 0, 0, 0));
    when(bars.continuityIssues(any(), any(), anyInt())).thenReturn(List.of());
    when(bars.gaps(any(), any(), anyInt())).thenReturn(List.of());
    when(bars.coverageByInstrument()).thenReturn(List.of());
    when(bars.phantomBars(anyInt())).thenReturn(List.of());
    BarSyncStateRepository states = mock(BarSyncStateRepository.class);
    when(states.findAll()).thenReturn(List.of());
    JobRunRepository jobs = mock(JobRunRepository.class);
    when(jobs.latestOf(any())).thenReturn(Optional.empty());

    BarAuditService.Report r =
        new BarAuditService(props(), scope, bars, days, states, jobs, null, Clock.systemUTC())
            .audit(d);

    BarAuditService.Check c =
        r.checks().stream().filter(x -> x.name().equals("completeness")).findFirst().orElseThrow();
    assertThat(c.samples()).containsExactly("CTVA");
    assertThat(
            r.checks().stream()
                .filter(x -> x.name().equals("rehab"))
                .findFirst()
                .orElseThrow()
                .samples())
        .as("复权因子新鲜度也只看 d 当天的目标")
        .containsExactlyInAnyOrder("AAPL", "CTVA");
  }

  private static org.jdkxx.trader.storage.marketdata.ConstituentRow member(
      long id, LocalDate until) {
    return new org.jdkxx.trader.storage.marketdata.ConstituentRow(
        IndexCode.SP500, id, "IT", "IT", "GICS", LocalDate.of(2026, 9, 3), until, "test");
  }
}
