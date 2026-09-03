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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 每日增量：刷新交易日历 → 算每只缺的交易日数 → 走订阅轮转补齐（缺口 + overlap 根，幂等覆盖）→ 刷新池与持仓的复权因子。
 */
public class DailyIncrementService {

    private static final Logger log = LoggerFactory.getLogger(DailyIncrementService.class);
    /** 美股收盘 16:00 ET；给券商落数据留 15 分钟，之前不把"今天"当成应有的收盘 K。 */
    static final LocalTime CLOSE_SETTLED = LocalTime.of(16, 15);

    private final MarketDataProperties props;
    private final MarketDataGateway gateway;
    private final TradingDayRepository tradingDays;
    private final DailyBarRepository bars;
    private final UniverseScope scope;
    private final RotationRefresher rotation;
    private final DeepBackfillService deep;
    private final Clock clock;
    private final ZoneId zone;

    public DailyIncrementService(MarketDataProperties props, MarketDataGateway gateway, TradingDayRepository tradingDays,
                                 DailyBarRepository bars, UniverseScope scope, RotationRefresher rotation,
                                 DeepBackfillService deep, Clock clock) {
        this.props = props;
        this.gateway = gateway;
        this.tradingDays = tradingDays;
        this.bars = bars;
        this.scope = scope;
        this.rotation = rotation;
        this.deep = deep;
        this.clock = clock;
        this.zone = ZoneId.of(props.zone());
    }

    public String run(JobContext ctx) throws Exception {
        ctx.progress("刷新交易日历");
        LocalDate today = LocalDate.now(clock.withZone(zone));
        List<TradingDay> days = gateway.tradingDays(Market.US, today.minusDays(60), today.plusDays(10)).get(30, TimeUnit.SECONDS);
        tradingDays.upsertAll(days);
        LocalDate expected = expectedLatestTradingDay(tradingDays.between(Market.US, today.minusDays(60), today), ZonedDateTime.now(clock.withZone(zone)));
        if (expected == null) {
            return "交易日历为空，未做增量";
        }
        List<LocalDate> calendar = tradingDays.between(Market.US, today.minusYears(5), expected);
        Map<Long, LocalDate> latest = bars.latestDates();

        List<InstrumentRow> targets = props.refresh().universeIncrement() ? union(scope.universe(), scope.poolAndHoldings()) : scope.poolAndHoldings();
        Map<Long, Integer> plan = new LinkedHashMap<>();
        for (InstrumentRow r : targets) {
            plan.put(r.id(), countFor(latest.get(r.id()), expected, calendar));
        }
        long need = plan.values().stream().filter(n -> n > 0).count();
        ctx.progress("增量目标 " + targets.size() + " 只，其中 " + need + " 只需要补到 " + expected);
        RotationRefresher.Result r = rotation.refresh(targets, row -> plan.getOrDefault(row.id(), 0), "增量", ctx);

        ctx.progress("刷新复权因子（池/持仓每日，全量每周）");
        String rehab = deep.refreshRehab(false, ctx);
        return "增量到 " + expected + "：目标 " + targets.size() + " 只，需补 " + r.instruments() + " 只，成功 " + r.ok() + "，失败 " + r.failed()
                + "，写入 K 线 " + r.bars() + "；" + rehab;
    }

    /** 当前时刻应当已经有收盘 K 的最近交易日。 */
    static LocalDate expectedLatestTradingDay(List<LocalDate> tradingDaysAsc, ZonedDateTime nowEt) {
        LocalDate today = nowEt.toLocalDate();
        boolean settled = nowEt.toLocalTime().isAfter(CLOSE_SETTLED);
        LocalDate best = null;
        for (LocalDate d : tradingDaysAsc) {
            if (d.isAfter(today) || (d.equals(today) && !settled)) {
                break;
            }
            best = d;
        }
        return best;
    }

    /** 缺几根就补几根再加 overlap；从未拉过的按全量根数。 */
    int countFor(LocalDate latest, LocalDate expected, List<LocalDate> calendar) {
        if (latest == null) {
            return props.refresh().fullCount();
        }
        if (!latest.isBefore(expected)) {
            return 0;
        }
        int missing = 0;
        for (LocalDate d : calendar) {
            if (d.isAfter(latest) && !d.isAfter(expected)) {
                missing++;
            }
        }
        return missing == 0 ? 0 : Math.min(1000, missing + props.refresh().overlap());
    }

    private static List<InstrumentRow> union(List<InstrumentRow> a, List<InstrumentRow> b) {
        Map<Long, InstrumentRow> m = new LinkedHashMap<>();
        a.forEach(r -> m.put(r.id(), r));
        b.forEach(r -> m.put(r.id(), r));
        return List.copyOf(m.values());
    }
}
