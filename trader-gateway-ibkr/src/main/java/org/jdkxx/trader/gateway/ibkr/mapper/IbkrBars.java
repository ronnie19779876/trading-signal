package org.jdkxx.trader.gateway.ibkr.mapper;

import com.ib.client.Bar;
import com.ib.client.Decimal;
import org.jdkxx.trader.domain.selection.SplitAdjustedBar;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 盈透历史日线 → 领域。TRADES 已按拆股调整（2026-10-10 只读探针实测 NVDA、AAPL），这里不做任何复权。
 * 日线在 formatDate=1 时时间串是 {@code yyyyMMdd}；解析不了或价格非正的条目丢弃，不编造。
 */
public final class IbkrBars {

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter HEAD = DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss");
    private static final ZoneId EXCHANGE = ZoneId.of("America/New_York");

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

    /**
     * 最早数据时间（reqHeadTimestamp，formatDate=1）→ 美东交易日。时间串是 UTC 的 {@code yyyyMMdd-HH:mm:ss}；
     * 同一只两次查询的时刻可能不同（2026-10-17 实测 SKYD 先回 08:00、后回 13:30，日期都是 2025-08-07），只认日期。
     * 解析不了返回 null，不编造。
     */
    public static LocalDate headDay(String headTimestamp) {
        if (headTimestamp == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(headTimestamp.strip(), HEAD).atOffset(ZoneOffset.UTC).atZoneSameInstant(EXCHANGE).toLocalDate();
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
