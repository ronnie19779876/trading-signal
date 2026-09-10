package org.jdkxx.trader.core.marketdata.bars;

import org.jdkxx.trader.core.marketdata.MarketDataProperties;
import org.jdkxx.trader.core.marketdata.jobs.JobContext;
import org.jdkxx.trader.core.marketdata.universe.UniverseScope;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.TradingDay;
import org.jdkxx.trader.gateway.MarketDataGateway;
import org.jdkxx.trader.storage.marketdata.DailyBarRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 交易日历回补：两段拼接，幂等，几秒跑完。
 *
 * <p><b>券商段</b>：一次请求拿券商能给的全部。实测券商只能回到 2016-09-12——
 * 请求 21 年与请求 27 年返回完全相同的 2591 天，说明那是服务端硬边界而不是分页。
 *
 * <p><b>反推段</b>：更早的日期从已有日 K 线反推。池、持仓与基准都是大盘股，
 * 每个交易日都有成交，它们出现过的日期并集就是那段时间的交易日。
 * 反推行标记为 DERIVED 且只补空缺，不覆盖券商数据。
 */
public class CalendarBackfillService {

    /** 请求券商时的起点。给足余量，实际能回到哪由券商决定。 */
    static final LocalDate BROKER_REQUEST_FROM = LocalDate.of(2010, 1, 1);
    /** 日历要向后覆盖到今天之后多少天（增量判定"最近应有收盘 K 的交易日"要用）。 */
    static final int FORWARD_DAYS = 10;
    /**
     * 反推时一天至少要有几只标的成交才算交易日。
     * 实测富途给 SPY 在三个美股假日留了脏 K 线（成交额 0），只按"有没有 K 线"取并集会把假日算进来；
     * 真实交易日有 13 只以上同时成交，取 2 既能挡掉脏数据又离真实值很远。
     */
    static final int MIN_INSTRUMENTS_PER_DAY = 2;

    private final MarketDataProperties props;
    private final MarketDataGateway gateway;
    private final TradingDayRepository tradingDays;
    private final DailyBarRepository bars;
    private final UniverseScope scope;
    private final Clock clock;

    public CalendarBackfillService(MarketDataProperties props, MarketDataGateway gateway, TradingDayRepository tradingDays,
                                   DailyBarRepository bars, UniverseScope scope, Clock clock) {
        this.props = props;
        this.gateway = gateway;
        this.tradingDays = tradingDays;
        this.bars = bars;
        this.scope = scope;
        this.clock = clock;
    }

    public String run(JobContext ctx) throws Exception {
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(props.zone())));

        ctx.progress("向券商取交易日历");
        List<TradingDay> fromBroker = gateway.tradingDays(Market.US, BROKER_REQUEST_FROM, today.plusDays(FORWARD_DAYS))
                .get(30, TimeUnit.SECONDS);
        tradingDays.upsertAll(fromBroker);
        LocalDate brokerEarliest = fromBroker.stream().map(TradingDay::date).min(LocalDate::compareTo).orElse(null);
        ctx.progress("券商段 " + fromBroker.size() + " 天"
                + (brokerEarliest == null ? "" : "，最早 " + brokerEarliest));

        int derived = 0;
        if (brokerEarliest != null) {
            Set<Long> deep = new LinkedHashSet<>();
            scope.poolAndHoldings().forEach(r -> deep.add(r.id()));
            LocalDate from = earliestBar(deep);
            if (from != null && from.isBefore(brokerEarliest)) {
                ctx.progress("反推 " + from + " 至 " + brokerEarliest + " 的交易日（券商取不到）");
                List<LocalDate> dates = bars.distinctTradeDates(deep, from, brokerEarliest, MIN_INSTRUMENTS_PER_DAY);
                derived = tradingDays.insertDerived(Market.US, dates);
                ctx.progress("反推段候选 " + dates.size() + " 天，新增 " + derived);
            }
        }

        TradingDayRepository.Coverage c = tradingDays.coverage(Market.US);
        return "交易日历：券商段 " + fromBroker.size() + " 天"
                + (brokerEarliest == null ? "" : "（最早 " + brokerEarliest + "）")
                + "，反推新增 " + derived + " 天；现覆盖 " + c.earliest() + " 至 " + c.latest()
                + " 共 " + c.days() + " 天（券商 " + c.fromBroker() + "，反推 " + c.derived() + "）";
    }

    /** 深度标的里最早的一根 K 线；没有深度标的就不反推。 */
    private LocalDate earliestBar(Set<Long> instrumentIds) {
        if (instrumentIds.isEmpty()) {
            return null;
        }
        return bars.coverageByInstrument().stream()
                .filter(c -> instrumentIds.contains(c.instrumentId()))
                .map(DailyBarRepository.InstrumentCoverage::earliest)
                .filter(java.util.Objects::nonNull)
                .min(LocalDate::compareTo).orElse(null);
    }

    /** 深度标的的最早 K 线日期，审计用来判断反推段跑没跑到位。 */
    public LocalDate earliestDeepBar() {
        Set<Long> ids = scope.poolAndHoldings().stream().map(InstrumentRow::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return earliestBar(ids);
    }
}
