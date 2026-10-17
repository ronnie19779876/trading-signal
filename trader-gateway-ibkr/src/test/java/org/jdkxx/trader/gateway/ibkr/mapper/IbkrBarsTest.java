package org.jdkxx.trader.gateway.ibkr.mapper;

import com.ib.client.Bar;
import com.ib.client.Decimal;
import org.jdkxx.trader.domain.selection.SplitAdjustedBar;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IbkrBarsTest {

    private static Bar bar(String time, double close) {
        return new Bar(time, close, close + 1, close - 1, close, Decimal.get(1234), 10, Decimal.get(close));
    }

    @Test
    void 按日期区间截取_价格原样不复权() {
        List<SplitAdjustedBar> out = IbkrBars.toSplitAdjusted(List.of(
                        bar("20151231", 10), bar("20160104", 119.78), bar("20160105", 120), bar("20160201", 121)),
                LocalDate.of(2016, 1, 1), LocalDate.of(2016, 1, 31));

        assertThat(out).extracting(SplitAdjustedBar::tradeDate)
                .containsExactly(LocalDate.of(2016, 1, 4), LocalDate.of(2016, 1, 5));
        assertThat(out.getFirst().close()).isEqualByComparingTo(new BigDecimal("119.78"));
        assertThat(out.getFirst().volume()).isEqualTo(1234);
    }

    @Test
    void 解析不了的日期与非正收盘丢弃_不编造() {
        List<SplitAdjustedBar> out = IbkrBars.toSplitAdjusted(List.of(bar("bad", 10), bar("20160104", 0), bar("20160105", 5)),
                LocalDate.of(2016, 1, 1), LocalDate.of(2016, 12, 31));

        assertThat(out).extracting(SplitAdjustedBar::tradeDate).containsExactly(LocalDate.of(2016, 1, 5));
    }

    @Test
    void 带时间的日期串取前八位() {
        assertThat(IbkrBars.day("20160104 00:00:00")).isEqualTo(LocalDate.of(2016, 1, 4));
        assertThat(IbkrBars.day("2016")).isNull();
    }

    /** 实测 SKYD 两次回 08:00 与 13:30（UTC），都是美东 2025-08-07；美东傍晚之后的 UTC 时刻已是次日，要折回美东。 */
    @Test
    void 最早数据时间按UTC折成美东日期_解析不了给null() {
        assertThat(IbkrBars.headDay("20250807-13:30:00")).isEqualTo(LocalDate.of(2025, 8, 7));
        assertThat(IbkrBars.headDay("20250807-08:00:00")).isEqualTo(LocalDate.of(2025, 8, 7));
        assertThat(IbkrBars.headDay("20250808-01:00:00")).isEqualTo(LocalDate.of(2025, 8, 7));
        assertThat(IbkrBars.headDay("bad")).isNull();
        assertThat(IbkrBars.headDay(null)).isNull();
    }
}
