package org.jdkxx.trader.core.marketdata;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.jdkxx.trader.core.marketdata.bars.CalendarBackfillService;
import org.jdkxx.trader.core.marketdata.bars.DailyIncrementService;
import org.jdkxx.trader.core.marketdata.bars.DeepBackfillService;
import org.jdkxx.trader.core.marketdata.bars.RotationRefresher;
import org.jdkxx.trader.core.marketdata.jobs.JobService;
import org.jdkxx.trader.core.marketdata.universe.UniverseScope;
import org.jdkxx.trader.core.marketdata.universe.UniverseSyncService;
import org.jdkxx.trader.domain.HistoryQuota;
import org.jdkxx.trader.domain.IndexCode;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.PoolRole;
import org.jdkxx.trader.gateway.MarketDataGateway;
import org.jdkxx.trader.storage.marketdata.BarSyncState;
import org.jdkxx.trader.storage.marketdata.BarSyncStateRepository;
import org.jdkxx.trader.storage.marketdata.ConstituentRow;
import org.jdkxx.trader.storage.marketdata.DailyBarRepository;
import org.jdkxx.trader.storage.marketdata.IndexConstituentRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;

/**
 * REST-facing facade: job triggers, constituent / instrument views, coverage statistics, quota.
 * Every trigger goes through {@link JobService#submit}, so a manual trigger while another job runs
 * fails fast with IllegalStateException instead of queueing.
 */
public class MarketDataFacade {

  private final MarketDataProperties props;
  private final JobService jobs;
  private final UniverseSyncService sync;
  private final UniverseScope scope;
  private final RotationRefresher rotation;
  private final DeepBackfillService deep;
  private final DailyIncrementService increment;
  private final CalendarBackfillService calendar;
  private final TradingDayRepository tradingDays;
  private final InstrumentRepository instruments;
  private final IndexConstituentRepository constituents;
  private final DailyBarRepository bars;
  private final BarSyncStateRepository states;
  private final MarketDataGateway gateway;
  private final InstrumentDirectory directory;

  public MarketDataFacade(
      MarketDataProperties props,
      JobService jobs,
      UniverseSyncService sync,
      UniverseScope scope,
      RotationRefresher rotation,
      DeepBackfillService deep,
      DailyIncrementService increment,
      CalendarBackfillService calendar,
      TradingDayRepository tradingDays,
      InstrumentRepository instruments,
      IndexConstituentRepository constituents,
      DailyBarRepository bars,
      BarSyncStateRepository states,
      MarketDataGateway gateway,
      InstrumentDirectory directory) {
    this.props = props;
    this.jobs = jobs;
    this.sync = sync;
    this.scope = scope;
    this.rotation = rotation;
    this.deep = deep;
    this.increment = increment;
    this.calendar = calendar;
    this.tradingDays = tradingDays;
    this.instruments = instruments;
    this.constituents = constituents;
    this.bars = bars;
    this.states = states;
    this.gateway = gateway;
    this.directory = directory;
  }

  // ------------------------------------------------------------------ Jobs

  public long syncUniverse(String trigger) {
    return syncUniverse(trigger, false);
  }

  /** {@code force=true} bypasses the exit-count guard (see {@link UniverseSyncService#apply}). */
  public long syncUniverse(String trigger, boolean force) {
    return jobs.submit(Jobs.UNIVERSE_SYNC, trigger, ctx -> sync.sync(ctx, force));
  }

  /**
   * Manual full rotation: fetch the last {@code count} (1..1000) daily bars for every constituent.
   * Uses the shared subscription quota in batches of 90, so do not run it on the dev instance while
   * production is live (see the pitfall table in CLAUDE.md).
   */
  public long refreshUniverse(String trigger, int count) {
    int n = Math.max(1, Math.min(count, 1000));
    return jobs.submit(
        Jobs.UNIVERSE_REFRESH,
        trigger,
        ctx -> {
          List<InstrumentRow> targets = scope.universe();
          RotationRefresher.Result r = rotation.refresh(targets, row -> n, "全量轮转", ctx);
          return "全量轮转 "
              + n
              + " 根：目标 "
              + r.instruments()
              + " 只，成功 "
              + r.ok()
              + "，失败 "
              + r.failed()
              + "，写入 K 线 "
              + r.bars();
        });
  }

  public long backfill(String symbol, String trigger) {
    InstrumentRow row = directory.require(symbol);
    return jobs.submit(
        Jobs.DEEP_BACKFILL,
        trigger,
        ctx -> row.symbol() + " 深度回补 " + deep.backfill(row, ctx) + " 根");
  }

  public long backfillPending(String trigger) {
    return jobs.submit(Jobs.DEEP_BACKFILL, trigger, deep::backfillPending);
  }

  public long increment(String trigger) {
    return jobs.submit(Jobs.DAILY_INCREMENT, trigger, increment::run);
  }

  public long refreshRehab(String trigger, boolean all) {
    return jobs.submit(Jobs.REHAB_REFRESH, trigger, ctx -> deep.refreshRehab(all, ctx));
  }

  public long backfillCalendar(String trigger) {
    return jobs.submit(Jobs.CALENDAR_BACKFILL, trigger, calendar::run);
  }

  public List<TradingDayRepository.Day> calendar(java.time.LocalDate from, java.time.LocalDate to) {
    return tradingDays.list(Market.US, from, to);
  }

  /**
   * Deep scan for gaps against the trading calendar. The previous-close continuity check cannot
   * find this kind of problem (Futu's SPY history misses 26 days whose last_close is
   * self-consistent); only comparing with the calendar can.
   */
  public List<GapView> gaps(java.time.LocalDate from, java.time.LocalDate to, int limit) {
    Map<Long, InstrumentRow> byId = new HashMap<>();
    instruments.findAll().forEach(r -> byId.put(r.id(), r));
    List<GapView> out = new ArrayList<>();
    for (DailyBarRepository.InstrumentGap g : bars.gaps(from, to, limit)) {
      InstrumentRow r = byId.get(g.instrumentId());
      out.add(
          new GapView(
              r == null ? "#" + g.instrumentId() : r.symbol(),
              r == null ? null : r.name(),
              g.missing(),
              g.firstMissing(),
              g.lastMissing()));
    }
    return out;
  }

  public record GapView(
      String symbol,
      String name,
      long missing,
      java.time.LocalDate firstMissing,
      java.time.LocalDate lastMissing) {}

  /**
   * Phantom-bar cleanup: by default a dry run that only lists them; apply=true actually deletes.
   * The broker has served dirty bars on US market holidays (turnover 0, absurd prices); those days
   * are not in the calendar, and leaving them in makes a backtest that walks an instrument's own
   * series see extra trading days.
   *
   * <p><b>The three numbers share one basis</b> (fixed in 3.1.2): {@code found} is the total,
   * {@code listed} (= the length of {@code bars}) is how many were listed and may be deleted this
   * round, and {@code deleted} can only be 0 or {@code listed}. Before, the dry run listed at most
   * 200 while the delete removed everything matching the condition, so "saw 200, deleted thousands"
   * was possible; the audit, in turn, capped its count at 20, so the three places all disagreed.
   *
   * <p>{@code deletePhantomBars(found)} deletes only the listed rows and re-checks the phantom
   * condition in SQL, so a row that stopped being a phantom between listing and deleting (calendar
   * refreshed) is kept. Deleted bars only come back through a backfill, which may bring the dirty
   * bar back too; hence the dry run first.
   */
  public PhantomCleanup cleanupPhantomBars(boolean apply) {
    int total = bars.phantomBarCount();
    List<DailyBarRepository.PhantomBar> found = bars.phantomBars(DailyBarRepository.PHANTOM_BATCH);
    List<PhantomView> view =
        found.stream()
            .map(
                p -> {
                  InstrumentRow row = instruments.findById(p.instrumentId()).orElse(null);
                  return new PhantomView(
                      row == null ? "#" + p.instrumentId() : row.symbol(),
                      p.tradeDate(),
                      p.open(),
                      p.high(),
                      p.low(),
                      p.close(),
                      p.volume(),
                      p.turnover());
                })
            .toList();
    int deleted = apply ? bars.deletePhantomBars(found) : 0;
    return new PhantomCleanup(apply, total, view.size(), deleted, total - deleted, view);
  }

  public record PhantomView(
      String symbol,
      java.time.LocalDate tradeDate,
      java.math.BigDecimal open,
      java.math.BigDecimal high,
      java.math.BigDecimal low,
      java.math.BigDecimal close,
      long volume,
      java.math.BigDecimal turnover) {}

  /**
   * applied=false means a dry run only; deleted is non-zero only for a real delete. found is the
   * total, listed is how many were listed this round (i.e. the most this round will delete),
   * remaining is what is left afterwards. When found &gt; listed another round is needed.
   */
  public record PhantomCleanup(
      boolean applied, int found, int listed, int deleted, int remaining, List<PhantomView> bars) {}

  public List<UniverseSyncService.IndexResult> importCsv(String csv) {
    return importCsv(csv, false);
  }

  public List<UniverseSyncService.IndexResult> importCsv(String csv, boolean force) {
    return sync.importCsv(csv, force);
  }

  // ------------------------------------------------------------------ Views

  public record InstrumentView(
      String symbol,
      String name,
      String nameCn,
      String type,
      String resolveStatus,
      boolean delisted,
      List<String> indexes,
      String sector,
      String subIndustry,
      String role,
      String depth,
      java.time.LocalDate earliest,
      java.time.LocalDate latest,
      int barCount,
      String lastError) {}

  public List<InstrumentView> universe(IndexCode index, PoolRole role) {
    Map<Long, List<ConstituentRow>> byInstrument =
        constituents.currentAll().stream()
            .collect(Collectors.groupingBy(ConstituentRow::instrumentId));
    Map<Long, PoolRole> roles = scope.roles();
    Map<Long, BarSyncState> st =
        states.findAll().stream().collect(Collectors.toMap(BarSyncState::instrumentId, s -> s));
    Map<Long, DailyBarRepository.InstrumentCoverage> cov =
        bars.coverageByInstrument().stream()
            .collect(Collectors.toMap(DailyBarRepository.InstrumentCoverage::instrumentId, c -> c));
    List<InstrumentView> out = new ArrayList<>();
    for (InstrumentRow r : instruments.findAll()) {
      List<ConstituentRow> cons = byInstrument.getOrDefault(r.id(), List.of());
      PoolRole pr = roles.get(r.id());
      if (index != null && cons.stream().noneMatch(c -> c.index() == index)) {
        continue;
      }
      if (role != null && pr != role) {
        continue;
      }
      if (index == null && role == null && cons.isEmpty() && pr == null) {
        // historical instruments in neither an index nor the pool are not listed by default
        continue;
      }
      out.add(view(r, cons, pr, st.get(r.id()), cov.get(r.id())));
    }
    return out;
  }

  /**
   * Instruments in the pool (holdings, pool, benchmarks), ordered first by the declaration order of
   * {@link PoolRole}, then by market and symbol within a role; item for item the same as the old
   * "call universe() once per role, three times". Only pool members are queried and only their bar
   * coverage is aggregated: the old way ran three GROUP BYs over the whole daily bar table per call
   * (0.6 seconds in production, 20~30 seconds on the dev database, 2026-10-14).
   */
  public List<InstrumentView> pool() {
    Map<Long, PoolRole> roles = scope.roles();
    if (roles.isEmpty()) {
      return List.of();
    }
    Map<Long, List<ConstituentRow>> byInstrument =
        constituents.currentAll().stream()
            .collect(Collectors.groupingBy(ConstituentRow::instrumentId));
    Map<Long, BarSyncState> st =
        states.findAll().stream().collect(Collectors.toMap(BarSyncState::instrumentId, s -> s));
    Map<Long, DailyBarRepository.InstrumentCoverage> cov =
        bars.coverageByInstrument(roles.keySet()).stream()
            .collect(Collectors.toMap(DailyBarRepository.InstrumentCoverage::instrumentId, c -> c));
    return instruments.findByIds(roles.keySet()).stream()
        .sorted(
            java.util.Comparator.comparing((InstrumentRow r) -> roles.get(r.id()))
                .thenComparing(InstrumentRow::market)
                .thenComparing(InstrumentRow::symbol))
        .map(
            r ->
                view(
                    r,
                    byInstrument.getOrDefault(r.id(), List.of()),
                    roles.get(r.id()),
                    st.get(r.id()),
                    cov.get(r.id())))
        .toList();
  }

  public InstrumentView instrument(String symbol) {
    InstrumentRow r = directory.require(symbol);
    List<ConstituentRow> cons =
        constituents.currentAll().stream().filter(c -> c.instrumentId() == r.id()).toList();
    return view(
        r,
        cons,
        scope.roles().get(r.id()),
        states.find(r.id()).orElse(null),
        bars.coverage(r.id()).orElse(null));
  }

  /**
   * Coverage range and row count come from real statistics over {@code daily_bar}, not from {@code
   * bar_sync_state}: its bar_count is <b>the number of rows written by the most recent sync</b>
   * (overwritten each time), and the daily increment always re-fetches 6 bars per instrument, so
   * every instrument showed 6 (measured 2026-09-19: AAPL showed 6, actual 5051). The range comes
   * from the same real source for the same reason, so it shrinks after a phantom-bar cleanup. Depth
   * and last error still come from the sync state.
   */
  private static InstrumentView view(
      InstrumentRow r,
      List<ConstituentRow> cons,
      PoolRole role,
      BarSyncState s,
      DailyBarRepository.InstrumentCoverage c) {
    ConstituentRow first = cons.isEmpty() ? null : cons.get(0);
    return new InstrumentView(
        r.symbol(),
        r.name(),
        r.nameCn(),
        r.type().name(),
        r.resolveStatus(),
        r.delisted(),
        cons.stream().map(x -> x.index().name()).sorted().toList(),
        first == null ? null : first.sector(),
        first == null ? null : first.subIndustry(),
        role == null ? null : role.name(),
        s == null ? null : s.depth(),
        c == null ? null : c.earliest(),
        c == null ? null : c.latest(),
        c == null ? 0 : (int) c.rows(),
        s == null ? null : s.lastError());
  }

  public record CoverageView(
      long rows,
      long instruments,
      java.time.LocalDate earliest,
      java.time.LocalDate latest,
      int universeSize,
      int poolSize,
      int holdingSize,
      int benchmarkSize,
      long unresolved,
      int universeCovered,
      int deepCovered,
      int withErrors,
      int rehabCovered,
      CalendarView calendar,
      QuotaView quota,
      JobService.Running runningJob) {}

  /**
   * Trading calendar coverage. The broker only goes back to about 2016-09; earlier days are derived
   * from daily bars.
   */
  public record CalendarView(
      java.time.LocalDate earliest,
      java.time.LocalDate latest,
      long days,
      long fromBroker,
      long derived) {}

  public record QuotaView(int used, int remain, int total, String detail) {}

  public CoverageView coverage() {
    DailyBarRepository.Coverage c = bars.coverage();
    List<InstrumentRow> universe = scope.universe();
    Map<Long, PoolRole> roles = scope.roles();
    Map<Long, BarSyncState> st =
        states.findAll().stream().collect(Collectors.toMap(BarSyncState::instrumentId, s -> s));
    int universeCovered =
        (int)
            universe.stream()
                .filter(r -> st.containsKey(r.id()) && st.get(r.id()).latest() != null)
                .count();
    int deepCovered =
        (int)
            roles.keySet().stream()
                .filter(
                    id -> st.containsKey(id) && BarSyncState.DEPTH_HIST.equals(st.get(id).depth()))
                .count();
    int withErrors = (int) st.values().stream().filter(s -> s.lastError() != null).count();
    int rehabCovered =
        (int)
            universe.stream()
                .filter(r -> st.containsKey(r.id()) && st.get(r.id()).rehabFetchedAt() != null)
                .count();
    long unresolved =
        instruments.findAll().stream().filter(r -> "UNRESOLVED".equals(r.resolveStatus())).count();
    TradingDayRepository.Coverage cal = tradingDays.coverage(Market.US);
    return new CoverageView(
        c.rows(),
        c.instruments(),
        c.earliest(),
        c.latest(),
        universe.size(),
        (int) roles.values().stream().filter(r -> r == PoolRole.POOL).count(),
        (int) roles.values().stream().filter(r -> r == PoolRole.HOLDING).count(),
        (int) roles.values().stream().filter(r -> r == PoolRole.BENCHMARK).count(),
        unresolved,
        universeCovered,
        deepCovered,
        withErrors,
        rehabCovered,
        new CalendarView(cal.earliest(), cal.latest(), cal.days(), cal.fromBroker(), cal.derived()),
        quota(),
        jobs.current().orElse(null));
  }

  /**
   * Futu history-bar quota (rolling 7-day window). Never throws: a gateway that is down or slow
   * yields a view with -1 values and the reason, so the coverage page still renders.
   */
  public QuotaView quota() {
    try {
      HistoryQuota q = gateway.historyQuota().get(10, TimeUnit.SECONDS);
      return new QuotaView(
          q.used(), q.remain(), q.total(), "7 天滚动窗口；预留 " + props.history().quotaReserve());
    } catch (Exception e) {
      return new QuotaView(-1, -1, -1, "额度查询失败：" + rootMessage(e));
    }
  }

  private static String rootMessage(Throwable t) {
    Throwable c = t;
    while (c.getCause() != null) {
      c = c.getCause();
    }
    return c.getMessage() == null ? c.toString() : c.getMessage();
  }

  public Map<String, Object> stats() {
    Map<String, Object> m = new HashMap<>();
    m.put("instruments", instruments.count());
    return m;
  }
}
