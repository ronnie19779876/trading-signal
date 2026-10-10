package org.jdkxx.trader.gateway.ibkr.mapper;

import com.ib.client.Bar;
import com.ib.client.Decimal;
import org.jdkxx.trader.domain.selection.SplitAdjustedBar;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 盈透历史日线 → 领域。TRADES 已按拆股调整（§24.3 实测 NVDA、AAPL），这里不做任何复权。
 * 日线在 formatDate=1 时时间串是 {@code yyyyMMdd}；解析不了或价格非正的条目丢弃，不编造。
 */
public final class IbkrBars {

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private IbkrBars() {
    }

    public static List<SplitAdjustedBar> toSplitAdjusted(List<Bar> bars, LocalDate from, LocalDate to) {
        List<SplitAdjustedBar> out = new ArrayList<>(bars.size());
        for (Bar b : bars) {
            LocalDate d = day(b.time());
            if (d == null || d.isBefore(from) || d.isAfter(to) || !(b.close() > 0)) {
                continue;
            }
            out.add(new SplitAdjustedBar(d, dec(b.open()), dec(b.high()), dec(b.low()), dec(b.close()), volume(b.volume())));
        }
        return out;
    }

    static LocalDate day(String time) {
        if (time == null || time.length() < 8) {
            return null;
        }
        try {
            return LocalDate.parse(time.substring(0, 8), DAY);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static BigDecimal dec(double v) {
        return v > 0 && Double.isFinite(v) ? BigDecimal.valueOf(v) : null;
    }

    private static long volume(Decimal v) {
        return v == null || !v.isValid() ? 0 : v.value().longValue();
    }
}
