package org.jdkxx.trader.core.marketdata.fundamentals;

import org.jdkxx.trader.core.marketdata.MarketDataProperties;
import org.jdkxx.trader.core.marketdata.bars.DailyIncrementService;
import org.jdkxx.trader.core.marketdata.jobs.JobContext;
import org.jdkxx.trader.core.marketdata.universe.UniverseScope;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.ValuationSnapshot;
import org.jdkxx.trader.gateway.MarketDataGateway;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;
import org.jdkxx.trader.storage.marketdata.ValuationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 估值快照作业：全量 ∪ 池 ∪ 持仓，每交易日收盘后一次。
 * 快照接口一次最多 400 只、不占订阅额度也不占历史 K 线额度，所以 500 多只只要两次调用，
 * 不会挤占已经跑稳的日 K 线链路。
 *
 * <p>市值与市盈率随价格实时变动，收盘前取到的是盘中值。作业排在每日增量之后，
 * 交易日的判定与增量共用一套：收盘 16:15 之后才算当天。
 */
public class ValuationSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(ValuationSnapshotService.class);

    private final MarketDataProperties props;
    private final UniverseScope scope;
    private final MarketDataGateway gateway;
    private final ValuationRepository valuations;
    private final TradingDayRepository tradingDays;
    private final Clock clock;

    public ValuationSnapshotService(MarketDataProperties props, UniverseScope scope, MarketDataGateway gateway,
                                    ValuationRepository valuations, TradingDayRepository tradingDays, Clock clock) {
        this.props = props;
        this.scope = scope;
        this.gateway = gateway;
        this.valuations = valuations;
        this.tradingDays = tradingDays;
        this.clock = clock;
    }

    public String run(JobContext ctx) throws Exception {
        ZoneId zone = ZoneId.of(props.zone());
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(zone));
        LocalDate today = now.toLocalDate();
        LocalDate tradeDate = DailyIncrementService.expectedLatestTradingDay(
                tradingDays.between(Market.US, today.minusDays(45), today), now);
        if (tradeDate == null) {
            return "交易日历为空，未取快照";
        }

        Map<Instrument, Long> ids = new LinkedHashMap<>();
        for (InstrumentRow r : union(scope.universe(), scope.poolAndHoldings())) {
            ids.put(new Instrument(r.market(), r.symbol()), r.id());
        }
        List<Instrument> targets = List.copyOf(ids.keySet());
        int batch = Math.min(props.fundamentals().snapshotBatchSize(), MarketDataGateway.SNAPSHOT_BATCH);
        ctx.progress("估值快照 " + targets.size() + " 只，分 " + ((targets.size() + batch - 1) / batch) + " 批");

        int written = 0;
        int failed = 0;
        for (int from = 0; from < targets.size(); from += batch) {
            if (ctx.cancelled()) {
                ctx.partial("已取消");
                break;
            }
            List<Instrument> slice = targets.subList(from, Math.min(from + batch, targets.size()));
            try {
                List<ValuationSnapshot> got = gateway.snapshots(slice).get(60, TimeUnit.SECONDS);
                written += valuations.upsertAll(ids, got, tradeDate);
                ctx.progress("估值快照 " + Math.min(from + batch, targets.size()) + "/" + targets.size()
                        + "（写入 " + written + "）");
            } catch (Exception e) {
                failed += slice.size();
                log.warn("估值快照一批失败（{} 只）：{}", slice.size(), e.toString());
                ctx.partial("一批快照失败：" + e.getMessage());
            }
        }
        return "估值快照 " + tradeDate + "：目标 " + targets.size() + " 只，写入 " + written + "，失败 " + failed;
    }

    private static List<InstrumentRow> union(List<InstrumentRow> a, List<InstrumentRow> b) {
        Map<Long, InstrumentRow> m = new LinkedHashMap<>();
        a.forEach(r -> m.put(r.id(), r));
        b.forEach(r -> m.put(r.id(), r));
        return new ArrayList<>(m.values());
    }
}
