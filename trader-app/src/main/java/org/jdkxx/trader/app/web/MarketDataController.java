package org.jdkxx.trader.app.web;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import org.jdkxx.trader.core.marketdata.MarketDataFacade;
import org.jdkxx.trader.core.marketdata.PoolService;
import org.jdkxx.trader.core.marketdata.audit.BarAuditService;
import org.jdkxx.trader.core.marketdata.bars.BarQueryService;
import org.jdkxx.trader.core.marketdata.jobs.JobService;
import org.jdkxx.trader.core.marketdata.universe.UniverseSyncService;
import org.jdkxx.trader.domain.Adjustment;
import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.IndexCode;
import org.jdkxx.trader.domain.PoolRole;
import org.jdkxx.trader.domain.RehabFactor;
import org.jdkxx.trader.storage.marketdata.JobRunRow;
import org.jdkxx.trader.storage.marketdata.PoolRow;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Market-data foundation: index constituents, the instrument pool, bars, batch jobs. Only wired
 * when storage is enabled.
 */
@RestController
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class MarketDataController {

  private final MarketDataFacade facade;
  private final PoolService pool;
  private final BarQueryService bars;
  private final JobService jobs;
  private final BarAuditService audit;

  public MarketDataController(
      MarketDataFacade facade,
      PoolService pool,
      BarQueryService bars,
      JobService jobs,
      BarAuditService audit) {
    this.facade = facade;
    this.pool = pool;
    this.bars = bars;
    this.jobs = jobs;
    this.audit = audit;
  }

  /** Daily-bar audit: audits the latest trading day that should have a closing bar by default. */
  @GetMapping("/api/bars/audit")
  public BarAuditService.Report audit(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate date) {
    return audit.audit(date);
  }

  /**
   * Trading-calendar backfill: the broker's range (from about 2016-09) plus earlier days derived
   * from daily bars. Idempotent, a few seconds.
   */
  @PostMapping("/api/bars/calendar/backfill")
  public Map<String, Object> backfillCalendar() {
    return Map.of("jobId", facade.backfillCalendar("MANUAL"));
  }

  /**
   * Trading days, the last year by default. source=FUTU comes from the broker, DERIVED is inferred
   * from daily bars.
   */
  @GetMapping("/api/bars/calendar")
  public List<TradingDayRepository.Day> calendar(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    LocalDate end = to == null ? LocalDate.now().plusDays(10) : to;
    LocalDate start = from == null ? end.minusYears(1) : from;
    if (start.isAfter(end)) {
      throw new IllegalArgumentException("from 不能晚于 to");
    }
    return facade.calendar(start, end);
  }

  /**
   * Deep scan for gaps against the trading calendar (full history by default). The previous-close
   * continuity check cannot find these: when the broker is missing days, its own previous close is
   * consistent with the gap.
   */
  @GetMapping("/api/bars/gaps")
  public List<MarketDataFacade.GapView> gaps(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(defaultValue = "50") int limit) {
    LocalDate end = to == null ? LocalDate.now() : to;
    LocalDate start = from == null ? LocalDate.of(2000, 1, 1) : from;
    if (start.isAfter(end)) {
      throw new IllegalArgumentException("from 不能晚于 to");
    }
    return facade.gaps(start, end, Math.clamp(limit, 1, 500));
  }

  /**
   * Phantom-bar cleanup: bars that fall outside the trading calendar (the broker has served dirty
   * data on US market holidays). The default apply=false is a dry run that only lists them; after
   * checking the list, use apply=true to actually delete.
   */
  @PostMapping("/api/bars/cleanup/phantom")
  public MarketDataFacade.PhantomCleanup cleanupPhantom(
      @RequestParam(defaultValue = "false") boolean apply) {
    return facade.cleanupPhantomBars(apply);
  }

  // ------------------------------------------------------------------ index constituents /
  // instruments

  /**
   * force=true bypasses the "removals per sync" guard: a legitimate annual reconstitution gets
   * blocked once; let it through after confirming the source is correct.
   */
  @PostMapping("/api/universe/sync")
  public Map<String, Object> syncUniverse(@RequestParam(defaultValue = "false") boolean force) {
    return Map.of("jobId", facade.syncUniverse("MANUAL", force));
  }

  @PostMapping(value = "/api/universe/import", consumes = "text/plain")
  public List<UniverseSyncService.IndexResult> importCsv(
      @RequestBody String csv, @RequestParam(defaultValue = "false") boolean force) {
    return facade.importCsv(csv, force);
  }

  @GetMapping("/api/universe")
  public List<MarketDataFacade.InstrumentView> universe(
      @RequestParam(required = false) String index, @RequestParam(required = false) String role) {
    return facade.universe(
        index == null ? null : parse(IndexCode.class, index),
        role == null ? null : parse(PoolRole.class, role));
  }

  @GetMapping("/api/universe/{symbol}")
  public MarketDataFacade.InstrumentView instrument(@PathVariable String symbol) {
    return facade.instrument(symbol);
  }

  // ------------------------------------------------------------------ instrument pool

  /**
   * Pool members of every role. Do not hard-code a list of roles here: a new role gets missed
   * (BENCHMARK was missed once).
   */
  @GetMapping("/api/pool")
  public List<MarketDataFacade.InstrumentView> pool() {
    return facade.pool();
  }

  @PostMapping("/api/pool/{symbol}")
  public PoolService.AddResult addToPool(
      @PathVariable String symbol,
      @RequestParam(defaultValue = "POOL") String role,
      @RequestParam(required = false) String note) {
    PoolRole r = parse(PoolRole.class, role);
    if (r == PoolRole.HOLDING) {
      throw new IllegalStateException(
          "HOLDING 由盈透持仓自动维护，不接受手工添加；要立刻按持仓同步用 POST /api/account/holdings/sync?apply=true");
    }
    return pool.add(symbol, r, note);
  }

  @DeleteMapping("/api/pool/{symbol}")
  public Map<String, Object> removeFromPool(@PathVariable String symbol) {
    boolean removed = pool.remove(symbol);
    if (!removed) {
      throw new NoSuchElementException(symbol.toUpperCase(Locale.ROOT) + " 不在标的池里");
    }
    return Map.of("removed", true);
  }

  // ------------------------------------------------------------------ bars and batch jobs

  @PostMapping("/api/bars/refresh/universe")
  public Map<String, Object> refreshUniverse(@RequestParam(defaultValue = "1000") int count) {
    return Map.of("jobId", facade.refreshUniverse("MANUAL", count));
  }

  @PostMapping("/api/bars/backfill/{symbol}")
  public Map<String, Object> backfill(@PathVariable String symbol) {
    return Map.of("jobId", facade.backfill(symbol, "MANUAL"));
  }

  @PostMapping("/api/bars/backfill")
  public Map<String, Object> backfillPending() {
    return Map.of("jobId", facade.backfillPending("MANUAL"));
  }

  @PostMapping("/api/bars/increment")
  public Map<String, Object> increment() {
    return Map.of("jobId", facade.increment("MANUAL"));
  }

  @PostMapping("/api/bars/rehab/refresh")
  public Map<String, Object> refreshRehab(@RequestParam(defaultValue = "false") boolean all) {
    return Map.of("jobId", facade.refreshRehab("MANUAL", all));
  }

  @GetMapping("/api/bars/coverage")
  public MarketDataFacade.CoverageView coverage() {
    return facade.coverage();
  }

  @GetMapping("/api/bars/quota")
  public MarketDataFacade.QuotaView quota() {
    return facade.quota();
  }

  @GetMapping("/api/bars/{symbol}")
  public List<DailyBar> bars(
      @PathVariable String symbol,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(defaultValue = "none") String adjust) {
    LocalDate end = to == null ? LocalDate.now() : to;
    LocalDate start = from == null ? end.minusDays(90) : from;
    return bars.bars(symbol, start, end, parse(Adjustment.class, adjust));
  }

  @GetMapping("/api/bars/{symbol}/rehab")
  public List<RehabFactor> rehab(@PathVariable String symbol) {
    return bars.rehab(symbol);
  }

  @GetMapping("/api/jobs")
  public Map<String, Object> jobList(@RequestParam(defaultValue = "20") int limit) {
    return Map.of(
        "running",
        jobs.current().map(r -> (Object) r).orElse(Map.of()),
        "recent",
        jobs.latest(limit));
  }

  @GetMapping("/api/jobs/{id}")
  public JobRunRow job(@PathVariable long id) {
    return jobs.find(id).orElseThrow(() -> new NoSuchElementException("作业 #" + id + " 不存在"));
  }

  @PostMapping("/api/jobs/cancel")
  public Map<String, Object> cancel() {
    jobs.cancel();
    return Map.of("cancelRequested", true);
  }

  private static <E extends Enum<E>> E parse(Class<E> type, String raw) {
    try {
      return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "参数非法：" + raw + "，可选 " + java.util.Arrays.toString(type.getEnumConstants()));
    }
  }

  /** Lets a PoolRow be serialized directly as well (spare helper). */
  static Map<String, Object> view(PoolRow r) {
    return Map.of(
        "instrumentId", r.instrumentId(), "role", r.role().name(), "addedAt", r.addedAt());
  }
}
