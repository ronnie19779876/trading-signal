package org.jdkxx.trader.core.marketdata;

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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 面向 REST 的门面：作业触发、成分股/标的视图、覆盖统计、额度。
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

    public MarketDataFacade(MarketDataProperties props, JobService jobs, UniverseSyncService sync, UniverseScope scope,
                            RotationRefresher rotation, DeepBackfillService deep, DailyIncrementService increment,
                            CalendarBackfillService calendar, TradingDayRepository tradingDays,
                            InstrumentRepository instruments, IndexConstituentRepository constituents, DailyBarRepository bars,
                            BarSyncStateRepository states, MarketDataGateway gateway, InstrumentDirectory directory) {
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

    // ------------------------------------------------------------------ 作业

    public long syncUniverse(String trigger) {
        return jobs.submit(Jobs.UNIVERSE_SYNC, trigger, sync::sync);
    }

    public long refreshUniverse(String trigger, int count) {
        int n = Math.max(1, Math.min(count, 1000));
        return jobs.submit(Jobs.UNIVERSE_REFRESH, trigger, ctx -> {
            List<InstrumentRow> targets = scope.universe();
            RotationRefresher.Result r = rotation.refresh(targets, row -> n, "全量轮转", ctx);
            return "全量轮转 " + n + " 根：目标 " + r.instruments() + " 只，成功 " + r.ok() + "，失败 " + r.failed() + "，写入 K 线 " + r.bars();
        });
    }

    public long backfill(String symbol, String trigger) {
        InstrumentRow row = directory.require(symbol);
        return jobs.submit(Jobs.DEEP_BACKFILL, trigger, ctx -> row.symbol() + " 深度回补 " + deep.backfill(row, ctx) + " 根");
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

    /** 对照交易日历深扫缺口。前收连续性检查查不出这类问题，只有比对日历才行。 */
    public List<GapView> gaps(java.time.LocalDate from, java.time.LocalDate to, int limit) {
        Map<Long, InstrumentRow> byId = new HashMap<>();
        instruments.findAll().forEach(r -> byId.put(r.id(), r));
        List<GapView> out = new ArrayList<>();
        for (DailyBarRepository.InstrumentGap g : bars.gaps(from, to, limit)) {
            InstrumentRow r = byId.get(g.instrumentId());
            out.add(new GapView(r == null ? "#" + g.instrumentId() : r.symbol(),
                    r == null ? null : r.name(), g.missing(), g.firstMissing(), g.lastMissing()));
        }
        return out;
    }

    public record GapView(String symbol, String name, long missing,
                          java.time.LocalDate firstMissing, java.time.LocalDate lastMissing) {
    }

    /**
     * 幽灵 K 线订正：默认只试跑列出清单，apply=true 才真删。
     * 券商在美股假日给过脏 K 线（成交额 0、价格离谱），日历里没有这些天，留着会让按标的自身序列
     * 遍历的回测多出交易日。
     */
    public PhantomCleanup cleanupPhantomBars(boolean apply) {
        List<DailyBarRepository.PhantomBar> found = bars.phantomBars(200);
        List<PhantomView> view = found.stream().map(p -> {
            InstrumentRow row = instruments.findById(p.instrumentId()).orElse(null);
            return new PhantomView(row == null ? "#" + p.instrumentId() : row.symbol(), p.tradeDate(),
                    p.open(), p.high(), p.low(), p.close(), p.volume(), p.turnover());
        }).toList();
        int deleted = apply && !found.isEmpty() ? bars.deletePhantomBars() : 0;
        return new PhantomCleanup(apply, view.size(), deleted, view);
    }

    public record PhantomView(String symbol, java.time.LocalDate tradeDate, java.math.BigDecimal open,
                              java.math.BigDecimal high, java.math.BigDecimal low, java.math.BigDecimal close,
                              long volume, java.math.BigDecimal turnover) {
    }

    /** applied=false 表示只是试跑；deleted 只有真删时才非零。 */
    public record PhantomCleanup(boolean applied, int found, int deleted, List<PhantomView> bars) {
    }

    public List<UniverseSyncService.IndexResult> importCsv(String csv) {
        return sync.importCsv(csv);
    }

    // ------------------------------------------------------------------ 视图

    public record InstrumentView(String symbol, String name, String nameCn, String type, String resolveStatus,
                                 boolean delisted, List<String> indexes, String sector, String subIndustry, String role,
                                 String depth, java.time.LocalDate earliest, java.time.LocalDate latest, int barCount, String lastError) {
    }

    public List<InstrumentView> universe(IndexCode index, PoolRole role) {
        Map<Long, List<ConstituentRow>> byInstrument = constituents.currentAll().stream()
                .collect(Collectors.groupingBy(ConstituentRow::instrumentId));
        Map<Long, PoolRole> roles = scope.roles();
        Map<Long, BarSyncState> st = states.findAll().stream().collect(Collectors.toMap(BarSyncState::instrumentId, s -> s));
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
                continue;   // 既不在指数也不在池的历史标的默认不列
            }
            out.add(view(r, cons, pr, st.get(r.id())));
        }
        return out;
    }

    public InstrumentView instrument(String symbol) {
        InstrumentRow r = directory.require(symbol);
        List<ConstituentRow> cons = constituents.currentAll().stream().filter(c -> c.instrumentId() == r.id()).toList();
        return view(r, cons, scope.roles().get(r.id()), states.find(r.id()).orElse(null));
    }

    private static InstrumentView view(InstrumentRow r, List<ConstituentRow> cons, PoolRole role, BarSyncState s) {
        ConstituentRow first = cons.isEmpty() ? null : cons.get(0);
        return new InstrumentView(r.symbol(), r.name(), r.nameCn(), r.type().name(), r.resolveStatus(), r.delisted(),
                cons.stream().map(c -> c.index().name()).sorted().toList(),
                first == null ? null : first.sector(), first == null ? null : first.subIndustry(),
                role == null ? null : role.name(),
                s == null ? null : s.depth(), s == null ? null : s.earliest(), s == null ? null : s.latest(),
                s == null ? 0 : s.barCount(), s == null ? null : s.lastError());
    }

    public record CoverageView(long rows, long instruments, java.time.LocalDate earliest, java.time.LocalDate latest,
                               int universeSize, int poolSize, int holdingSize, int benchmarkSize, long unresolved,
                               int universeCovered, int deepCovered, int withErrors, int rehabCovered,
                               CalendarView calendar, QuotaView quota, JobService.Running runningJob) {
    }

    /** 交易日历覆盖。券商只能给到约 2016-09，更早的是从日 K 线反推的。 */
    public record CalendarView(java.time.LocalDate earliest, java.time.LocalDate latest, long days,
                               long fromBroker, long derived) {
    }

    public record QuotaView(int used, int remain, int total, String detail) {
    }

    public CoverageView coverage() {
        DailyBarRepository.Coverage c = bars.coverage();
        List<InstrumentRow> universe = scope.universe();
        Map<Long, PoolRole> roles = scope.roles();
        Map<Long, BarSyncState> st = states.findAll().stream().collect(Collectors.toMap(BarSyncState::instrumentId, s -> s));
        int universeCovered = (int) universe.stream().filter(r -> st.containsKey(r.id()) && st.get(r.id()).latest() != null).count();
        int deepCovered = (int) roles.keySet().stream().filter(id -> st.containsKey(id) && BarSyncState.DEPTH_HIST.equals(st.get(id).depth())).count();
        int withErrors = (int) st.values().stream().filter(s -> s.lastError() != null).count();
        int rehabCovered = (int) universe.stream().filter(r -> st.containsKey(r.id()) && st.get(r.id()).rehabFetchedAt() != null).count();
        long unresolved = instruments.findAll().stream().filter(r -> "UNRESOLVED".equals(r.resolveStatus())).count();
        TradingDayRepository.Coverage cal = tradingDays.coverage(Market.US);
        return new CoverageView(c.rows(), c.instruments(), c.earliest(), c.latest(), universe.size(),
                (int) roles.values().stream().filter(r -> r == PoolRole.POOL).count(),
                (int) roles.values().stream().filter(r -> r == PoolRole.HOLDING).count(),
                (int) roles.values().stream().filter(r -> r == PoolRole.BENCHMARK).count(),
                unresolved, universeCovered, deepCovered, withErrors, rehabCovered,
                new CalendarView(cal.earliest(), cal.latest(), cal.days(), cal.fromBroker(), cal.derived()),
                quota(), jobs.current().orElse(null));
    }

    public QuotaView quota() {
        try {
            HistoryQuota q = gateway.historyQuota().get(10, TimeUnit.SECONDS);
            return new QuotaView(q.used(), q.remain(), q.total(), "7 天滚动窗口；预留 " + props.history().quotaReserve());
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
