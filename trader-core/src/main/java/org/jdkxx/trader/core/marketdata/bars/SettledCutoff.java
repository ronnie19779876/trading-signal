package org.jdkxx.trader.core.marketdata.bars;

import org.jdkxx.trader.core.marketdata.SnapshotWindow;
import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * K 线写库截止日：此刻已经收盘落定的最近交易日，晚于它的 K 线（盘中没收完的当天那根）一律不写。
 *
 * <p>为什么要有：盘中触发的深度回补（盈透盘中重连后持仓同步新增的标的、手工加池）与手工轮转，
 * 会把当天没收完的 K 线存下来；当晚的增量看到"最新日期已经是应有日期"就不再重拉，
 * 盘中价被当成收盘价一直留着，账户快照也按它估值（2.0.2 前的缺陷）。
 */
public class SettledCutoff {

    private final TradingDayRepository days;
    private final ZoneId zone;
    private final Clock clock;

    public SettledCutoff(TradingDayRepository days, ZoneId zone, Clock clock) {
        this.days = days;
        this.zone = zone;
        this.clock = clock;
    }

    public LocalDate current() {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(zone));
        LocalDate today = now.toLocalDate();
        LocalDate expected = DailyIncrementService.expectedLatestTradingDay(days.between(Market.US, today.minusDays(45), today), now);
        if (expected != null) {
            return expected;
        }
        // 日历为空（刚建库还没刷日历）：至少保证不写今天没落定的那根
        return now.toLocalTime().isBefore(SnapshotWindow.OPENS) ? today.minusDays(1) : today;
    }

    /** 去掉晚于截止日的 K 线。 */
    public static List<DailyBar> settled(List<DailyBar> bars, LocalDate cutoff) {
        return bars.stream().filter(b -> !b.tradeDate().isAfter(cutoff)).toList();
    }
}
