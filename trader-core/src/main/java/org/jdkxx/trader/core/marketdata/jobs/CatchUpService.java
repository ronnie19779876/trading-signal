package org.jdkxx.trader.core.marketdata.jobs;

import org.jdkxx.trader.core.marketdata.MarketDataProperties;
import org.jdkxx.trader.core.marketdata.bars.DailyIncrementService;
import org.jdkxx.trader.core.marketdata.universe.UniverseScope;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.storage.marketdata.DailyBarRepository;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;
import org.jdkxx.trader.storage.marketdata.ValuationRepository;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 当天补偿检查：收盘后晚些时候确认当日数据齐了，缺什么由调度器补跑。
 *
 * <p>为什么需要它：调度碰撞重试只能盖住"作业线程被占用"，盖不住"该跑的时候应用正在重启"、
 * "作业 FAILED"、"服务器当时没开"。而估值快照是<b>时点数据</b>——券商的快照接口不接受日期参数，
 * 错过当天就永远没有那天的市值与市盈率。
 *
 * <p>为什么这个时点还补得回来：券商在收盘后冻结 curPrice，直到次日盘前（04:00 ET）才更新。
 * 实测美东 20:49 取快照，写入的仍是当日收盘口径（市值 = 股本 × 当日收盘价，误差 0.0000%）。
 * 所以 16:00 ET 到次日 04:00 ET 之间补跑都有效；作业排在 21:00 ET，离窗口关闭还有 7 小时。
 *
 * <p>本服务<b>只做判断不提交作业</b>：它跑在调度线程而不是作业线程上，
 * 补跑仍走调度器那套带重试的提交路径，避免自己占着作业线程却又要提交作业的死结。
 */
public class CatchUpService {

    /** 当天该有什么、实际有什么。 */
    public record Gap(LocalDate expected, boolean tradingDay, int targets, long withBars, long withValuation) {

        public boolean barsMissing() {
            return tradingDay && withBars < targets;
        }

        public boolean valuationMissing() {
            return tradingDay && withValuation < targets;
        }

        public String describe() {
            if (!tradingDay) {
                return expected == null ? "交易日历为空，跳过补偿检查" : "今天非交易日，无需补偿（最近交易日 " + expected + "）";
            }
            return expected + " 补偿检查：目标 " + targets + " 只，K 线 " + withBars + "，估值 " + withValuation;
        }
    }

    private final MarketDataProperties props;
    private final UniverseScope scope;
    private final DailyBarRepository bars;
    private final ValuationRepository valuations;
    private final TradingDayRepository tradingDays;
    private final Clock clock;

    public CatchUpService(MarketDataProperties props, UniverseScope scope, DailyBarRepository bars,
                          ValuationRepository valuations, TradingDayRepository tradingDays, Clock clock) {
        this.props = props;
        this.scope = scope;
        this.bars = bars;
        this.valuations = valuations;
        this.tradingDays = tradingDays;
        this.clock = clock;
    }

    public Gap check() {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(ZoneId.of(props.zone())));
        LocalDate today = now.toLocalDate();
        LocalDate expected = DailyIncrementService.expectedLatestTradingDay(
                tradingDays.between(Market.US, today.minusDays(45), today), now);
        if (expected == null) {
            return new Gap(null, false, 0, 0, 0);
        }
        if (!expected.equals(today)) {
            // 今天不是交易日（周末或假日），没有当天数据要补
            return new Gap(expected, false, 0, 0, 0);
        }

        Set<Long> targets = new LinkedHashSet<>();
        scope.universe().forEach(r -> targets.add(r.id()));
        scope.poolAndHoldings().forEach(r -> targets.add(r.id()));
        long withBars = bars.instrumentIdsWithBarOn(expected).stream().filter(targets::contains).count();
        long withValuation = valuations.instrumentIdsOn(expected).stream().filter(targets::contains).count();
        return new Gap(expected, true, targets.size(), withBars, withValuation);
    }
}
