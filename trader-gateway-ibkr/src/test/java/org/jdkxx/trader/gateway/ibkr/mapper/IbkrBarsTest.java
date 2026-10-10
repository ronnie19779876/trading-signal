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
}
