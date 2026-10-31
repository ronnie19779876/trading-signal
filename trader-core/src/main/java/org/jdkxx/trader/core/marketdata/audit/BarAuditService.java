package org.jdkxx.trader.core.marketdata.audit;

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
import org.jdkxx.trader.core.marketdata.Jobs;
import org.jdkxx.trader.core.marketdata.MarketDataProperties;
import org.jdkxx.trader.core.marketdata.SnapshotWindow;
import org.jdkxx.trader.core.marketdata.bars.DailyIncrementService;
import org.jdkxx.trader.core.marketdata.universe.UniverseScope;
import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.domain.IndexCode;
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

/**
 * Daily bar audit: after the close, answers "is this day's full data set complete and correct".
 * Read-only, computed inside the application, so nobody needs a direct database connection. Each
 * check reports ok / explanation / count and samples; the overall ok = every critical check passed.
 *
 * <p>Rule for denominators: when auditing a past date d, every target set must be the one valid
 * <b>on d</b> ({@link UniverseScope#targetBreakdown(LocalDate)}), not today's list; otherwise a
 * weekend constituent sync makes Friday's audit report the newly added instruments as missing.
 */
public class BarAuditService {

  /**
   * The gap check only looks at this many recent days: operations can fill these by re-running the
   * increment; older ones are deep-scanned via /api/bars/gaps.
   */
  static final int GAP_WINDOW_DAYS = 90;

  /**
   * How far the constituent count may shrink relative to the index's nominal size before the check
   * fails. Multi-class shares make the real symbol count slightly larger than the nominal size.
   */
  static final int UNIVERSE_SHRINK_TOLERANCE_PERCENT = 5;

  public record Check(
      String name, boolean ok, boolean critical, String detail, long count, List<String> samples) {}

  public record Report(
      LocalDate date,
      boolean ok,
      Instant generatedAt,
      Map<String, Object> summary,
      List<Check> checks) {}

  private final MarketDataProperties props;
  private final UniverseScope scope;
  private final DailyBarRepository bars;
  private final TradingDayRepository tradingDays;
  private final BarSyncStateRepository states;
  private final JobRunRepository jobs;
  private final MarketDataGateway gateway;
  private final Clock clock;

  public BarAuditService(
      MarketDataProperties props,
      UniverseScope scope,
      DailyBarRepository bars,
      TradingDayRepository tradingDays,
      BarSyncStateRepository states,
      JobRunRepository jobs,
      MarketDataGateway gateway,
      Clock clock) {
    this.props = props;
    this.scope = scope;
    this.bars = bars;
    this.tradingDays = tradingDays;
    this.states = states;
    this.jobs = jobs;
    this.gateway = gateway;
    this.clock = clock;
  }

  /**
   * The most recent trading day that should already have a closing bar: from the trading calendar
   * (today only counts after 16:15 ET); without a calendar, falls back to the latest date in the
   * database.
   */
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

    // 0. A market holiday has no closing data, so the other checks are meaningless. A date outside
    //    the calendar's coverage is audited as usual ("not in the calendar" alone cannot prove the
    //    market was closed)
    if (tradingDays.covers(Market.US, d) && !tradingDays.isTradingDay(Market.US, d)) {
      summary.put("tradingDay", false);
      checks.add(new Check("calendar", true, true, d + " 美股休市，当天没有收盘数据", 0, List.of()));
      return new Report(d, true, clock.instant(), summary, checks);
    }
    summary.put("tradingDay", true);

    // 1. Universe ∪ pool ∪ holdings all have a bar on the day.
    // The detail prints the denominator's arithmetic **whether green or not** (changed in 3.1.4). A
    // silently shrinking denominator has bitten this project three times: constituents wrongly
    // removed (pure set difference), instruments marked UNRESOLVED (batch-level recovery writes
    // that itself), a trading halt on the day. Each time numerator and denominator dropped by one
    // together and this check stayed green.
    // Rather than adding yet another companion check each time, the arithmetic is kept in plain
    // sight.
    // The denominator is the list valid on d (3.4.0): after a weekend sync changed the
    // constituents, auditing last Friday before Monday's close must not count the new members as
    // missing (production false alarm on 2026-10-12 for SKYD, TWLO, VYLR).
    // The rehab freshness and sync-error checks below share this set and likewise only look at d's
    // targets.
    UniverseScope.TargetBreakdown b = scope.targetBreakdown(d);
    Map<Long, InstrumentRow> targets = new LinkedHashMap<>();
    b.expected().forEach(r -> targets.put(r.id(), r));
    Set<Long> have = bars.instrumentIdsWithBarOn(d);
    List<String> missing =
        targets.values().stream()
            .filter(r -> !have.contains(r.id()))
            .map(InstrumentRow::symbol)
            .sorted()
            .toList();
    summary.put("nominalTargets", b.nominal());
    summary.put("constituentTargets", b.constituents());
    summary.put("poolExtraTargets", b.poolExtras());
    summary.put("downgradedTargets", b.unusable().size());
    summary.put("targets", targets.size());
    summary.put("withBarOnDate", targets.size() - missing.size());
    String arithmetic =
        "应采 "
            + targets.size()
            + " = 名义 "
            + b.nominal()
            + "（成分股 "
            + b.constituents()
            + " + 池与持仓另加 "
            + b.poolExtras()
            + "）"
            + " − 降级 "
            + b.unusable().size()
            + " − 查不到标的行 "
            + b.missingRows();
    checks.add(
        new Check(
            "completeness",
            missing.isEmpty(),
            true,
            arithmetic
                + "；"
                + d
                + " 实采 "
                + (targets.size() - missing.size())
                + (missing.isEmpty()
                    ? "，没有缺的"
                    : "，缺 " + missing.size() + " 只：" + String.join("、", head(missing, 10))),
            missing.size(),
            head(missing, 30)));

    // 1b. Has the constituent set itself shrunk (added 2026-09-25) completeness above uses this set
    // as its denominator: when constituents are wrongly removed the denominator shrinks with them,
    // the check stays green, and that night's increment simply stops collecting those instruments,
    // so the absolute count has to be watched on its own. The guard is in
    // UniverseSyncService.apply. This one deliberately uses the current constituent counts (it
    // watches the state of the list now, not what was collected on d)
    Map<IndexCode, Integer> counts = scope.constituentCounts();
    List<String> shrunk = new ArrayList<>();
    for (Map.Entry<IndexCode, Integer> e : counts.entrySet()) {
      int floor = e.getKey().nominalSize() * (100 - UNIVERSE_SHRINK_TOLERANCE_PERCENT) / 100;
      if (e.getValue() < floor) {
        shrunk.add(
            e.getKey()
                + " 只有 "
                + e.getValue()
                + " 只（名义 "
                + e.getKey().nominalSize()
                + "，下限 "
                + floor
                + "）");
      }
    }
    summary.put("constituents", counts);
    checks.add(
        new Check(
            "universeSize",
            shrunk.isEmpty(),
            true,
            shrunk.isEmpty()
                ? counts.entrySet().stream()
                    .map(e -> e.getKey() + " " + e.getValue() + " 只")
                    .collect(Collectors.joining("、"))
                : "成分股集合缩水：" + String.join("；", shrunk) + "。查 UNIVERSE_SYNC 作业与来源页面结构",
            shrunk.size(),
            shrunk));

    // 1c. A downgraded instrument also shrinks completeness's denominator (added 2026-10-07)
    // Batch-level recovery (UnknownSymbolGuard) actively marks a symbol UNRESOLVED when Futu does
    // not know it, so it drops out of usable(): completeness loses one from numerator and
    // denominator together, the check stays green and nobody sees anything. So watch the absolute
    // count: how many of the nominal targets can really still be collected. This is guard three.
    List<InstrumentRow> unusable = b.unusable();
    int nominal = b.nominal();
    int collectable = nominal - unusable.size();
    int resolveFloor = nominal * (100 - UNIVERSE_SHRINK_TOLERANCE_PERCENT) / 100;
    List<String> downgraded =
        unusable.stream()
            .map(r -> r.symbol() + "(" + r.resolveStatus() + (r.delisted() ? ",已退市" : "") + ")")
            .sorted()
            .toList();
    summary.put("collectableTargets", collectable);
    checks.add(
        new Check(
            "resolveDowngrade",
            collectable >= resolveFloor,
            true,
            unusable.isEmpty()
                ? "目标 " + nominal + " 只全部可采集"
                : nominal
                    + " 只目标里 "
                    + unusable.size()
                    + " 只采不了（"
                    + String.join("、", head(downgraded, 10))
                    + "），还能采 "
                    + collectable
                    + " 只，下限 "
                    + resolveFloor
                    + "。代码改名是正常的，但要确认是真改名而不是富途侧出了事",
            unusable.size(),
            head(downgraded, 30)));

    // 2. Field sanity of the day's bars
    DailyBarRepository.DaySanity sanity = bars.sanityOn(d);
    long bad = sanity.ohlcInconsistent() + sanity.nonPositiveClose() + sanity.nullTurnover();
    checks.add(
        new Check(
            "sanity",
            bad == 0,
            true,
            d
                + " 共 "
                + sanity.bars()
                + " 根：OHLC 不一致 "
                + sanity.ohlcInconsistent()
                + "，非正收盘 "
                + sanity.nonPositiveClose()
                + "，成交额为空 "
                + sanity.nullTurnover()
                + "，空 K "
                + sanity.blank()
                + "，零成交量 "
                + sanity.zeroVolume(),
            bad,
            List.of()));
    summary.put("barsOnDate", sanity.bars());
    summary.put("blank", sanity.blank());
    summary.put("zeroVolume", sanity.zeroVolume());

    // 3. Previous-close continuity over the last 30 calendar days (a mismatch = a missed day or bad
    //    data). This query covers every instrument in the table, not only d's targets; non-targets
    //    show up as "#id". It cannot find a gap whose last_close is self-consistent (check 8
    //    compares with the calendar for that).
    // The count is the uncapped total from continuityIssueCount; the list (capped at 50) only
    // provides samples. Using the list length as the count stopped at 50 (found in the 2026-10-31
    // review, same trap as the phantom bars below)
    int issueTotal = bars.continuityIssueCount(d.minusDays(30), d);
    List<DailyBarRepository.ContinuityIssue> issues = bars.continuityIssues(d.minusDays(30), d, 50);
    Map<Long, InstrumentRow> byId = targets;
    List<String> samples =
        issues.stream()
            .map(
                i ->
                    (byId.containsKey(i.instrumentId())
                            ? byId.get(i.instrumentId()).symbol()
                            : "#" + i.instrumentId())
                        + " "
                        + i.tradeDate()
                        + " last_close="
                        + i.lastClose()
                        + " prev_close="
                        + i.prevClose())
            .toList();
    checks.add(
        new Check(
            "continuity",
            issueTotal == 0,
            true,
            issueTotal == 0 ? "最近 30 天前收与上一根收盘全部一致" : issueTotal + " 处前收与上一根收盘不一致（可能漏日）",
            issueTotal,
            head(samples, 20)));

    // 4. Price-adjustment factor freshness (refreshed within 8 days). Measured against now, not d:
    //    it is about the state of the factors, which are only ever current
    Instant stale = clock.instant().minus(Duration.ofDays(8));
    Map<Long, BarSyncState> st =
        states.findAll().stream().collect(Collectors.toMap(BarSyncState::instrumentId, s -> s));
    List<String> staleRehab =
        targets.values().stream()
            .filter(
                r ->
                    !st.containsKey(r.id())
                        || st.get(r.id()).rehabFetchedAt() == null
                        || st.get(r.id()).rehabFetchedAt().isBefore(stale))
            .map(InstrumentRow::symbol)
            .sorted()
            .toList();
    checks.add(
        new Check(
            "rehab",
            staleRehab.isEmpty(),
            false,
            staleRehab.isEmpty() ? "复权因子 8 天内全部刷新过" : staleRehab.size() + " 只复权因子超过 8 天未刷新",
            staleRehab.size(),
            head(staleRehab, 20)));

    // 5. Errors recorded in the sync state
    List<String> errors =
        targets.values().stream()
            .filter(r -> st.containsKey(r.id()) && st.get(r.id()).lastError() != null)
            .map(r -> r.symbol() + "：" + st.get(r.id()).lastError())
            .sorted()
            .toList();
    checks.add(
        new Check(
            "syncErrors",
            errors.isEmpty(),
            false,
            errors.isEmpty() ? "没有标的带着最近错误" : errors.size() + " 只标的最近一次同步有错误",
            errors.size(),
            head(errors, 20)));

    // 6. The most recent increment job
    Optional<JobRunRow> last = jobs.latestOf(Jobs.DAILY_INCREMENT);
    boolean jobOk = last.map(j -> "OK".equals(j.status())).orElse(false);
    checks.add(
        new Check(
            "incrementJob",
            jobOk,
            false,
            last.map(
                    j ->
                        "最近一次增量 #"
                            + j.id()
                            + " "
                            + j.status()
                            + "（"
                            + j.startedAt()
                            + "）："
                            + j.summary())
                .orElse("还没有跑过增量作业"),
            last.isPresent() ? 1 : 0,
            List.of()));

    // 7. Trading calendar coverage: it should reach back to the earliest bar of the targets,
    //    otherwise the derived segment never ran or missed part
    TradingDayRepository.Coverage cal = tradingDays.coverage(Market.US);
    LocalDate earliestDeep =
        bars.coverageByInstrument().stream()
            .filter(x -> targets.containsKey(x.instrumentId()))
            .map(DailyBarRepository.InstrumentCoverage::earliest)
            .filter(java.util.Objects::nonNull)
            .min(LocalDate::compareTo)
            .orElse(null);
    boolean calOk =
        cal.earliest() != null && (earliestDeep == null || !cal.earliest().isAfter(earliestDeep));
    summary.put("calendarDays", cal.days());
    checks.add(
        new Check(
            "calendarCoverage",
            calOk,
            false,
            cal.earliest() == null
                ? "交易日历为空，跑一次日历回补"
                : "交易日历 "
                    + cal.earliest()
                    + " 至 "
                    + cal.latest()
                    + " 共 "
                    + cal.days()
                    + " 天（券商 "
                    + cal.fromBroker()
                    + "，反推 "
                    + cal.derived()
                    + "）"
                    + (calOk ? "" : "；最早 K 线在 " + earliestDeep + "，日历没覆盖到，跑一次日历回补"),
            cal.days(),
            List.of()));

    // 8. Gaps in the recent window, compared with the calendar.
    // Only the last GAP_WINDOW_DAYS days: operations can fill these (re-run the increment); older
    // ones are mostly holes on the broker side that cannot be filled, and reporting red every day
    // would make the whole daily check meaningless, so they are deep-scanned on demand via GET
    // /api/bars/gaps.
    // An advisory check rather than a critical one, again because the usual cause is missing broker
    // data rather than a run we missed.
    // The filter is pushed down into SQL: LIMIT 20 first and then filtering by targets in memory
    // amounts to "truncate, then filter"; when the first 20 rows happened to be instruments we do
    // not care about, the result was empty and the daily check reported "no gaps" (fixed in 3.1.2).
    // The list is capped at 20 instruments and only provides samples; the instrument count and the
    // missing-day total come from gapTotals, which has no limit. Before 3.5.2 both were taken from
    // the capped list, so they stopped at 20 instruments and the days of the rest were dropped
    // (found in the 2026-10-31 review)
    DailyBarRepository.GapTotals gapTotals =
        bars.gapTotals(d.minusDays(GAP_WINDOW_DAYS), d, targets.keySet());
    List<DailyBarRepository.InstrumentGap> gaps =
        bars.gaps(d.minusDays(GAP_WINDOW_DAYS), d, 20, targets.keySet());
    List<String> gapSamples =
        gaps.stream()
            .map(
                g ->
                    symbolOf(targets, g.instrumentId())
                        + " 缺 "
                        + g.missing()
                        + " 天（"
                        + g.firstMissing()
                        + " ~ "
                        + g.lastMissing()
                        + "）")
            .toList();
    long missingDays = gapTotals.missingDays();
    summary.put("recentGapDays", missingDays);
    checks.add(
        new Check(
            "historyGaps",
            gapTotals.instruments() == 0,
            false,
            gapTotals.instruments() == 0
                ? "最近 " + GAP_WINDOW_DAYS + " 天对照交易日历没有缺口"
                : gapTotals.instruments()
                    + " 只在最近 "
                    + GAP_WINDOW_DAYS
                    + " 天有缺口，共 "
                    + missingDays
                    + " 天；能补的重跑增量，补不回来的多是券商缺数（深扫用 /api/bars/gaps）",
            missingDays,
            head(gapSamples, 20)));

    // 9. Phantom bars: rows inside the calendar's covered range but not on the calendar (the broker
    //    has served dirty data on US market holidays).
    // The count is the uncapped total; phantomBars(20) only provides samples. Using the length of a
    // capped list as the count reported 20 even for thousands (fixed in 3.1.2)
    int phantomTotal = bars.phantomBarCount();
    List<String> phantomSamples =
        bars.phantomBars(20).stream()
            .map(
                p ->
                    symbolOf(targets, p.instrumentId())
                        + " "
                        + p.tradeDate()
                        + "（收 "
                        + p.close()
                        + "，成交额 "
                        + p.turnover()
                        + "）")
            .toList();
    summary.put("phantomBars", phantomTotal);
    checks.add(
        new Check(
            "phantomBars",
            phantomTotal == 0,
            false,
            phantomTotal == 0
                ? "没有落在交易日历之外的 K 线"
                : phantomTotal
                    + " 根 K 线落在交易日历之外（券商脏数据），"
                    + "用 POST /api/bars/cleanup/phantom 先试跑再订正（一次最多订正 "
                    + DailyBarRepository.PHANTOM_BATCH
                    + " 条，删完再跑一轮）",
            phantomTotal,
            head(phantomSamples, 20)));

    // 10. Unsettled bars: a same-day bar written before the close settled (16:15 ET) carries an
    //     intraday price.
    // Since 2.0.2 writes are filtered by the cutoff, so none should appear; older ones are
    // re-fetched and overwritten by the next increment automatically, hence advisory only. The
    // count is the uncapped total from unsettledBarCount and the list only provides samples; before
    // 3.5.2 the count was the length of a list capped at 1000 (found in the 2026-10-31 review)
    ZoneId zone = ZoneId.of(props.zone());
    int unsettledTotal = bars.unsettledBarCount(zone, SnapshotWindow.OPENS);
    List<DailyBarRepository.UnsettledBar> unsettled =
        bars.unsettledBars(zone, SnapshotWindow.OPENS, 20);
    List<String> unsettledSamples =
        unsettled.stream()
            .map(
                u ->
                    symbolOf(targets, u.instrumentId())
                        + " "
                        + u.tradeDate()
                        + "（写入于美东 "
                        + u.fetchedAt().atZone(zone).toLocalDateTime().withNano(0)
                        + "）")
            .toList();
    summary.put("unsettledBars", unsettledTotal);
    checks.add(
        new Check(
            "unsettledBars",
            unsettledTotal == 0,
            false,
            unsettledTotal == 0
                ? "没有收盘落定前写入的 K 线"
                : unsettledTotal + " 根 K 线在当天收盘落定（美东 16:15）前写入，可能是盘中价；下一次增量会自动重拉覆盖",
            unsettledTotal,
            head(unsettledSamples, 20)));

    // 11. Futu gateway
    boolean futuUp =
        gateway instanceof BrokerGateway g && g.status().state() == GatewayState.CONNECTED;
    checks.add(
        new Check(
            "gateway",
            futuUp,
            false,
            futuUp ? "富途网关已连接" : "富途网关未连接（" + Broker.FUTU + "）",
            0,
            List.of()));

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
