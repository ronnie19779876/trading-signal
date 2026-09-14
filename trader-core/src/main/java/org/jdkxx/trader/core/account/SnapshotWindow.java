package org.jdkxx.trader.core.account;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * 账户快照的时间窗口：交易日美东 16:15（收盘数据落定）到次日 04:00（盘前开始、快照价不再是当日收盘）。
 *
 * <p>为什么要窗口：盈透只给"现在"的持仓与资金，不给历史。窗口外拍到的持仓不属于任何一个交易日的收盘，
 * 过去漏掉的日子也补不回来。
 */
public final class SnapshotWindow {

    static final LocalTime OPENS = LocalTime.of(16, 15);
    static final LocalTime CLOSES = LocalTime.of(4, 0);

    public static final String OUTSIDE = "现在不在账户快照窗口（交易日美东 16:15 至次日 04:00）。盈透只给当前持仓，过去的日子补不回来";

    private SnapshotWindow() {
    }

    /** 此刻拍快照属于哪个交易日；窗口外为空。 */
    public static Optional<LocalDate> asOfDate(ZonedDateTime nowEt, Predicate<LocalDate> isTradingDay) {
        LocalDate today = nowEt.toLocalDate();
        LocalTime t = nowEt.toLocalTime();
        if (!t.isBefore(OPENS) && isTradingDay.test(today)) {
            return Optional.of(today);
        }
        if (t.isBefore(CLOSES) && isTradingDay.test(today.minusDays(1))) {
            return Optional.of(today.minusDays(1));
        }
        return Optional.empty();
    }
}
