package org.jdkxx.trader.core.marketdata.fundamentals;

import org.jdkxx.trader.core.marketdata.Jobs;
import org.jdkxx.trader.core.marketdata.MarketDataProperties;
import org.jdkxx.trader.core.marketdata.audit.BarAuditService.Check;
import org.jdkxx.trader.core.marketdata.audit.BarAuditService.Report;
import org.jdkxx.trader.core.marketdata.bars.DailyIncrementService;
import org.jdkxx.trader.core.marketdata.universe.UniverseScope;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.storage.marketdata.FinancialRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.jdkxx.trader.storage.marketdata.JobRunRepository;
import org.jdkxx.trader.storage.marketdata.JobRunRow;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;
import org.jdkxx.trader.storage.marketdata.ValuationRepository;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 基本面数据审计，与日线审计并列，一起进收盘巡检。
 * 注意亏损股的市盈率为负是真实数据（实测），不算异常；ETF 没有市值口径，也不算缺失。
 */
public class FundamentalsAuditService {

    private final MarketDataProperties props;
    private final UniverseScope scope;
    private final ValuationRepository valuations;
    private final FinancialRepository financials;
    private final TradingDayRepository tradingDays;
    private final JobRunRepository jobs;
    private final Clock clock;

    public FundamentalsAuditService(MarketDataProperties props, UniverseScope scope, ValuationRepository valuations,
                                    FinancialRepository financials, TradingDayRepository tradingDays,
                                    JobRunRepository jobs, Clock clock) {
        this.props = props;
        this.scope = scope;
        this.valuations = valuations;
        this.financials = financials;
        this.tradingDays = tradingDays;
        this.jobs = jobs;
        this.clock = clock;
    }

    public LocalDate expectedDate() {
        ZoneId zone = ZoneId.of(props.zone());
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(zone));
        LocalDate today = now.toLocalDate();
        LocalDate expected = DailyIncrementService.expectedLatestTradingDay(
                tradingDays.between(Market.US, today.minusDays(45), today), now);
        return expected != null ? expected : valuations.maxTradeDate().orElse(today);
    }

    public Report audit(LocalDate date) {
        LocalDate d = date == null ? expectedDate() : date;
        List<Check> checks = new ArrayList<>();
        Map<String, Object> summary = new LinkedHashMap<>();

        if (tradingDays.covers(Market.US, d) && !tradingDays.isTradingDay(Market.US, d)) {
            summary.put("tradingDay", false);
            checks.add(new Check("calendar", true, true, d + " 美股休市，当天没有估值快照", 0, List.of()));
            return new Report(d, true, clock.instant(), summary, checks);
        }
        summary.put("tradingDay", true);

        // 1. 当天全量都有估值快照
        Map<Long, InstrumentRow> targets = new LinkedHashMap<>();
        scope.universe().forEach(r -> targets.put(r.id(), r));
        scope.poolAndHoldings().forEach(r -> targets.put(r.id(), r));
        Set<Long> have = valuations.instrumentIdsOn(d);
        List<String> missing = targets.values().stream().filter(r -> !have.contains(r.id()))
                .map(InstrumentRow::symbol).sorted().toList();
        summary.put("targets", targets.size());
        summary.put("withValuationOnDate", targets.size() - missing.size());
        checks.add(new Check("valuationCompleteness", missing.isEmpty(), true,
                missing.isEmpty() ? "全部 " + targets.size() + " 只在 " + d + " 都有估值快照"
                        : missing.size() + " 只缺 " + d + " 的估值快照",
                missing.size(), head(missing, 30)));

        // 2. 合理性：负市盈率与 ETF 无市值都正常，这里只报计数
        ValuationRepository.DayStats stats = valuations.statsOn(d);
        summary.put("valuationRows", stats.rows());
        summary.put("negativePe", stats.negativePe());
        summary.put("suspended", stats.suspended());
        checks.add(new Check("valuationSanity", stats.rows() > 0, true,
                d + " 共 " + stats.rows() + " 行：无市值 " + stats.missingMarketCap() + "（ETF 与指数正常没有），"
                        + "负市盈率 " + stats.negativePe() + "（亏损股正常），停牌 " + stats.suspended(),
                stats.rows(), List.of()));

        // 3. 池与持仓的财报陈旧度
        Map<Long, LocalDate> latest = new HashMap<>();
        for (FinancialRepository.Latest l : financials.latestPeriods()) {
            latest.merge(l.instrumentId(), l.periodEnd(), (a, b) -> a.isAfter(b) ? a : b);
        }
        LocalDate staleBefore = d.minusDays(props.fundamentals().financialStaleAfter().toDays());
        // 有财报但过旧 = 财报季没跟上，值得报；从来没有财报 = 多半是真基金（不像 REITs，富途也归为 Trust），只作提示
        List<InstrumentRow> pool = scope.poolAndHoldings();
        List<String> stale = pool.stream()
                .filter(r -> latest.get(r.id()) != null && latest.get(r.id()).isBefore(staleBefore))
                .map(r -> r.symbol() + "（最近 " + latest.get(r.id()) + "）")
                .sorted().toList();
        List<String> noReports = pool.stream().filter(r -> latest.get(r.id()) == null)
                .map(InstrumentRow::symbol).sorted().toList();
        summary.put("reports", financials.countReports());
        summary.put("poolWithoutReports", noReports.size());
        checks.add(new Check("financialsFreshness", stale.isEmpty(), false,
                (stale.isEmpty() ? "池与持仓有财报的标的都在 " + staleBefore + " 之后" : stale.size() + " 只财报过旧")
                        + (noReports.isEmpty() ? "" : "；另有 " + noReports.size() + " 只从来没有财报（基金正常没有）：" + head(noReports, 10)),
                stale.size(), head(stale, 20)));

        // 4. 最近一次估值作业
        Optional<JobRunRow> last = jobs.latestOf(Jobs.VALUATION_SNAPSHOT);
        checks.add(new Check("valuationJob", last.map(j -> "OK".equals(j.status())).orElse(false), false,
                last.map(j -> "最近一次估值作业 #" + j.id() + " " + j.status() + "（" + j.startedAt() + "）：" + j.summary())
                        .orElse("还没有跑过估值作业"),
                last.isPresent() ? 1 : 0, List.of()));

        boolean ok = checks.stream().filter(Check::critical).allMatch(Check::ok);
        return new Report(d, ok, clock.instant(), summary, checks);
    }

    private static List<String> head(List<String> list, int n) {
        return list.size() <= n ? list : list.subList(0, n);
    }
}
