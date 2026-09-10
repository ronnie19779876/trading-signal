package org.jdkxx.trader.core.marketdata.audit;

import org.jdkxx.trader.core.marketdata.Jobs;
import org.jdkxx.trader.core.marketdata.MarketDataProperties;
import org.jdkxx.trader.core.marketdata.bars.DailyIncrementService;
import org.jdkxx.trader.core.marketdata.universe.UniverseScope;
import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.gateway.BrokerGateway;
import org.jdkxx.trader.gateway.GatewayState;
import org.jdkxx.trader.gateway.MarketDataGateway;
import org.jdkxx.trader.storage.marketdata.BarSyncState;
import org.jdkxx.trader.storage.marketdata.BarSyncStateRepository;
import org.jdkxx.trader.storage.marketdata.DailyBarRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.jdkxx.trader.storage.marketdata.JobRunRepository;
import org.jdkxx.trader.storage.marketdata.JobRunRow;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 日线数据审计：收盘后回答"这一天全量数据齐不齐、对不对"。只读，在应用内算，不要求外部连库。
 * 每一项 check 给 ok / 说明 / 计数与样本；总 ok = 关键项全部通过。
 */
public class BarAuditService {

    /** 缺口检查只看最近这么多天：这段运维能靠重跑增量补上，更早的深扫走 /api/bars/gaps。 */
    static final int GAP_WINDOW_DAYS = 90;

    public record Check(String name, boolean ok, boolean critical, String detail, long count, List<String> samples) {
    }

    public record Report(LocalDate date, boolean ok, Instant generatedAt, Map<String, Object> summary, List<Check> checks) {
    }

    private final MarketDataProperties props;
    private final UniverseScope scope;
    private final DailyBarRepository bars;
    private final TradingDayRepository tradingDays;
    private final BarSyncStateRepository states;
    private final JobRunRepository jobs;
    private final MarketDataGateway gateway;
    private final Clock clock;

    public BarAuditService(MarketDataProperties props, UniverseScope scope, DailyBarRepository bars, TradingDayRepository tradingDays,
                           BarSyncStateRepository states, JobRunRepository jobs, MarketDataGateway gateway, Clock clock) {
        this.props = props;
        this.scope = scope;
        this.bars = bars;
        this.tradingDays = tradingDays;
        this.states = states;
        this.jobs = jobs;
        this.gateway = gateway;
        this.clock = clock;
    }

    /** 应当已经有收盘 K 的最近交易日：交易日历（16:15 ET 之后才算当天），没有日历时退回库里的最大日期。 */
    public LocalDate expectedDate() {
        ZoneId zone = ZoneId.of(props.zone());
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(zone));
        LocalDate today = now.toLocalDate();
        List<LocalDate> cal = tradingDays.between(Market.US, today.minusDays(45), today);
        LocalDate expected = DailyIncrementService.expectedLatestTradingDay(cal, now);
        return expected != null ? expected : bars.maxTradeDate().orElse(today);
    }

    public Report audit(LocalDate date) {
        LocalDate d = date == null ? expectedDate() : date;
        List<Check> checks = new ArrayList<>();
        Map<String, Object> summary = new LinkedHashMap<>();

        // 0. 休市日没有收盘数据，其余检查无从谈起；日历覆盖不到的日期照常审计（不能凭"不在日历里"判休市）
        if (tradingDays.covers(Market.US, d) && !tradingDays.isTradingDay(Market.US, d)) {
            summary.put("tradingDay", false);
            checks.add(new Check("calendar", true, true, d + " 美股休市，当天没有收盘数据", 0, List.of()));
            return new Report(d, true, clock.instant(), summary, checks);
        }
        summary.put("tradingDay", true);

        // 1. 全量 ∪ 池 ∪ 持仓 当天都有 K 线
        Map<Long, InstrumentRow> targets = new LinkedHashMap<>();
        scope.universe().forEach(r -> targets.put(r.id(), r));
        scope.poolAndHoldings().forEach(r -> targets.put(r.id(), r));
        Set<Long> have = bars.instrumentIdsWithBarOn(d);
        List<String> missing = targets.values().stream().filter(r -> !have.contains(r.id())).map(InstrumentRow::symbol).sorted().toList();
        summary.put("targets", targets.size());
        summary.put("withBarOnDate", targets.size() - missing.size());
        checks.add(new Check("completeness", missing.isEmpty(), true,
                missing.isEmpty() ? "全部 " + targets.size() + " 只在 " + d + " 都有 K 线" : missing.size() + " 只缺 " + d + " 的 K 线",
                missing.size(), head(missing, 30)));

        // 2. 当天 K 线的字段合理性
        DailyBarRepository.DaySanity sanity = bars.sanityOn(d);
        long bad = sanity.ohlcInconsistent() + sanity.nonPositiveClose() + sanity.nullTurnover();
        checks.add(new Check("sanity", bad == 0, true,
                d + " 共 " + sanity.bars() + " 根：OHLC 不一致 " + sanity.ohlcInconsistent() + "，非正收盘 " + sanity.nonPositiveClose()
                        + "，成交额为空 " + sanity.nullTurnover() + "，空 K " + sanity.blank() + "，零成交量 " + sanity.zeroVolume(),
                bad, List.of()));
        summary.put("barsOnDate", sanity.bars());
        summary.put("blank", sanity.blank());
        summary.put("zeroVolume", sanity.zeroVolume());

        // 3. 最近 30 个自然日的前收连续性（不等 = 漏日或数据错）
        List<DailyBarRepository.ContinuityIssue> issues = bars.continuityIssues(d.minusDays(30), d, 50);
        Map<Long, InstrumentRow> byId = targets;
        List<String> samples = issues.stream().map(i -> (byId.containsKey(i.instrumentId()) ? byId.get(i.instrumentId()).symbol() : "#" + i.instrumentId())
                + " " + i.tradeDate() + " last_close=" + i.lastClose() + " prev_close=" + i.prevClose()).toList();
        checks.add(new Check("continuity", issues.isEmpty(), true,
                issues.isEmpty() ? "最近 30 天前收与上一根收盘全部一致" : issues.size() + " 处前收与上一根收盘不一致（可能漏日）",
                issues.size(), head(samples, 20)));

        // 4. 复权因子新鲜度（8 天内刷新过）
        Instant stale = clock.instant().minus(Duration.ofDays(8));
        Map<Long, BarSyncState> st = states.findAll().stream().collect(Collectors.toMap(BarSyncState::instrumentId, s -> s));
        List<String> staleRehab = targets.values().stream()
                .filter(r -> !st.containsKey(r.id()) || st.get(r.id()).rehabFetchedAt() == null || st.get(r.id()).rehabFetchedAt().isBefore(stale))
                .map(InstrumentRow::symbol).sorted().toList();
        checks.add(new Check("rehab", staleRehab.isEmpty(), false,
                staleRehab.isEmpty() ? "复权因子 8 天内全部刷新过" : staleRehab.size() + " 只复权因子超过 8 天未刷新",
                staleRehab.size(), head(staleRehab, 20)));

        // 5. 同步状态里的错误
        List<String> errors = targets.values().stream()
                .filter(r -> st.containsKey(r.id()) && st.get(r.id()).lastError() != null)
                .map(r -> r.symbol() + "：" + st.get(r.id()).lastError()).sorted().toList();
        checks.add(new Check("syncErrors", errors.isEmpty(), false,
                errors.isEmpty() ? "没有标的带着最近错误" : errors.size() + " 只标的最近一次同步有错误", errors.size(), head(errors, 20)));

        // 6. 最近一次增量作业
        Optional<JobRunRow> last = jobs.latestOf(Jobs.DAILY_INCREMENT);
        boolean jobOk = last.map(j -> "OK".equals(j.status())).orElse(false);
        checks.add(new Check("incrementJob", jobOk, false,
                last.map(j -> "最近一次增量 #" + j.id() + " " + j.status() + "（" + j.startedAt() + "）：" + j.summary()).orElse("还没有跑过增量作业"),
                last.isPresent() ? 1 : 0, List.of()));

        // 7. 交易日历覆盖：应回到深度标的最早的 K 线，否则反推段没跑或跑漏了
        TradingDayRepository.Coverage cal = tradingDays.coverage(Market.US);
        LocalDate earliestDeep = bars.coverageByInstrument().stream()
                .filter(x -> targets.containsKey(x.instrumentId()))
                .map(DailyBarRepository.InstrumentCoverage::earliest)
                .filter(java.util.Objects::nonNull).min(LocalDate::compareTo).orElse(null);
        boolean calOk = cal.earliest() != null && (earliestDeep == null || !cal.earliest().isAfter(earliestDeep));
        summary.put("calendarDays", cal.days());
        checks.add(new Check("calendarCoverage", calOk, false,
                cal.earliest() == null ? "交易日历为空，跑一次日历回补"
                        : "交易日历 " + cal.earliest() + " 至 " + cal.latest() + " 共 " + cal.days()
                                + " 天（券商 " + cal.fromBroker() + "，反推 " + cal.derived() + "）"
                                + (calOk ? "" : "；最早 K 线在 " + earliestDeep + "，日历没覆盖到，跑一次日历回补"),
                cal.days(), List.of()));

        // 8. 最近窗口对照日历的缺口。
        // 只看最近 GAP_WINDOW_DAYS 天：这段是运维能补的（增量重跑）；更早的多是券商侧的洞，
        // 补不回来，天天报红会让整个巡检失去意义，改由 GET /api/bars/gaps 按需深扫。
        // 判为提示项而非关键项，同样因为常见成因是券商缺数而不是我们漏跑。
        List<DailyBarRepository.InstrumentGap> gaps = bars.gaps(d.minusDays(GAP_WINDOW_DAYS), d, 20).stream()
                .filter(g -> targets.containsKey(g.instrumentId())).toList();
        List<String> gapSamples = gaps.stream()
                .map(g -> symbolOf(targets, g.instrumentId()) + " 缺 " + g.missing() + " 天（"
                        + g.firstMissing() + " ~ " + g.lastMissing() + "）").toList();
        long missingDays = gaps.stream().mapToLong(DailyBarRepository.InstrumentGap::missing).sum();
        summary.put("recentGapDays", missingDays);
        checks.add(new Check("historyGaps", gaps.isEmpty(), false,
                gaps.isEmpty() ? "最近 " + GAP_WINDOW_DAYS + " 天对照交易日历没有缺口"
                        : gaps.size() + " 只在最近 " + GAP_WINDOW_DAYS + " 天有缺口，共 " + missingDays
                                + " 天；能补的重跑增量，补不回来的多是券商缺数（深扫用 /api/bars/gaps）",
                missingDays, head(gapSamples, 20)));

        // 9. 富途网关
        boolean futuUp = gateway instanceof BrokerGateway g && g.status().state() == GatewayState.CONNECTED;
        checks.add(new Check("gateway", futuUp, false, futuUp ? "富途网关已连接" : "富途网关未连接（" + Broker.FUTU + "）", 0, List.of()));

        boolean ok = checks.stream().filter(Check::critical).allMatch(Check::ok);
        return new Report(d, ok, clock.instant(), summary, checks);
    }

    private static String symbolOf(Map<Long, InstrumentRow> targets, long id) {
        InstrumentRow r = targets.get(id);
        return r == null ? "#" + id : r.symbol();
    }

    private static List<String> head(List<String> list, int n) {
        return list.size() <= n ? list : list.subList(0, n);
    }
}
