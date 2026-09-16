package org.jdkxx.trader.domain.signal;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * 判定用的一根日 K：已换算到判定口径（按拆股调整，价与量同口径），用 double 计算。
 * 判定是确定性纯函数，同一组输入在任何 JVM 上结果逐位相同（Java 17 起浮点运算恒为 strict）。
 */
public record SignalBar(LocalDate date, double open, double high, double low, double close, double volume) {

    public SignalBar {
        Objects.requireNonNull(date, "date");
        if (!(open > 0 && high > 0 && low > 0 && close > 0) || high < low || volume < 0
                || Double.isNaN(volume) || Double.isInfinite(high)) {
            throw new IllegalArgumentException("K 线数值非法：" + date);
        }
    }

    /** 升序且无重复日期，否则抛异常——乱序输入会让所有"前 N 根"的计算静默出错。 */
    static void requireAscending(List<SignalBar> bars) {
        for (int i = 1; i < bars.size(); i++) {
            if (!bars.get(i).date().isAfter(bars.get(i - 1).date())) {
                throw new IllegalArgumentException("K 线必须按日期严格升序：" + bars.get(i).date());
            }
        }
    }
}
