package org.jdkxx.trader.app.web;

import org.jdkxx.trader.core.marketdata.MarketDataFacade;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 行情数据底座：成分股、标的池、K 线、跑批。只在存储启用时装配。
 */
@RestController
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class MarketDataController {

    private final MarketDataFacade facade;
    private final PoolService pool;
    private final BarQueryService bars;
    private final JobService jobs;
    private final BarAuditService audit;

    public MarketDataController(MarketDataFacade facade, PoolService pool, BarQueryService bars, JobService jobs, BarAuditService audit) {
        this.facade = facade;
        this.pool = pool;
        this.bars = bars;
        this.jobs = jobs;
        this.audit = audit;
    }

    /** 日线数据审计：默认审最近一个应有收盘 K 的交易日。 */
    @GetMapping("/api/bars/audit")
    public BarAuditService.Report audit(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return audit.audit(date);
    }

    /** 交易日历回补：券商段（约 2016-09 起）+ 更早的从日 K 线反推。幂等，几秒。 */
    @PostMapping("/api/bars/calendar/backfill")
    public Map<String, Object> backfillCalendar() {
        return Map.of("jobId", facade.backfillCalendar("MANUAL"));
    }

    /** 交易日列表，默认最近一年。source=FUTU 是券商给的，DERIVED 是从日 K 线反推的。 */
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
     * 对照交易日历深扫缺口（默认全历史）。
     * 前收连续性检查发现不了这类问题：券商缺数时它自己的前收与缺口自洽。
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
     * 幽灵 K 线订正：落在交易日历之外的 K 线（券商在美股假日给过脏数据）。
     * 默认 apply=false 只试跑列清单，确认无误后再用 apply=true 真删。
     */
    @PostMapping("/api/bars/cleanup/phantom")
    public MarketDataFacade.PhantomCleanup cleanupPhantom(@RequestParam(defaultValue = "false") boolean apply) {
        return facade.cleanupPhantomBars(apply);
    }

    // ------------------------------------------------------------------ 成分股 / 标的

    @PostMapping("/api/universe/sync")
    public Map<String, Object> syncUniverse() {
        return Map.of("jobId", facade.syncUniverse("MANUAL"));
    }

    @PostMapping(value = "/api/universe/import", consumes = "text/plain")
    public List<UniverseSyncService.IndexResult> importCsv(@RequestBody String csv) {
        return facade.importCsv(csv);
    }

    @GetMapping("/api/universe")
    public List<MarketDataFacade.InstrumentView> universe(@RequestParam(required = false) String index,
                                                          @RequestParam(required = false) String role) {
        return facade.universe(index == null ? null : parse(IndexCode.class, index), role == null ? null : parse(PoolRole.class, role));
    }

    @GetMapping("/api/universe/{symbol}")
    public MarketDataFacade.InstrumentView instrument(@PathVariable String symbol) {
        return facade.instrument(symbol);
    }

    // ------------------------------------------------------------------ 标的池

    @GetMapping("/api/pool")
    public List<MarketDataFacade.InstrumentView> pool() {
        List<MarketDataFacade.InstrumentView> p = facade.universe(null, PoolRole.POOL);
        List<MarketDataFacade.InstrumentView> h = facade.universe(null, PoolRole.HOLDING);
        return java.util.stream.Stream.concat(p.stream(), h.stream()).toList();
    }

    @PostMapping("/api/pool/{symbol}")
    public PoolService.AddResult addToPool(@PathVariable String symbol, @RequestParam(defaultValue = "POOL") String role,
                                           @RequestParam(required = false) String note) {
        return pool.add(symbol, parse(PoolRole.class, role), note);
    }

    @DeleteMapping("/api/pool/{symbol}")
    public Map<String, Object> removeFromPool(@PathVariable String symbol) {
        boolean removed = pool.remove(symbol);
        if (!removed) {
            throw new NoSuchElementException(symbol.toUpperCase(Locale.ROOT) + " 不在标的池里");
        }
        return Map.of("removed", true);
    }

    // ------------------------------------------------------------------ K 线与跑批

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
    public List<DailyBar> bars(@PathVariable String symbol,
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
        return Map.of("running", jobs.current().map(r -> (Object) r).orElse(Map.of()), "recent", jobs.latest(limit));
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
            throw new IllegalArgumentException("参数非法：" + raw + "，可选 " + java.util.Arrays.toString(type.getEnumConstants()));
        }
    }

    /** 让 PoolRow 也能直接序列化（备用）。 */
    static Map<String, Object> view(PoolRow r) {
        return Map.of("instrumentId", r.instrumentId(), "role", r.role().name(), "addedAt", r.addedAt());
    }
}
