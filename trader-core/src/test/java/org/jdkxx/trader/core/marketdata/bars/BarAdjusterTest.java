package org.jdkxx.trader.core.marketdata.bars;

import org.jdkxx.trader.domain.Adjustment;
import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.RehabFactor;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BarAdjusterTest {

    private static final Instrument AAPL = Instrument.us("AAPL");

    private static DailyBar bar(String date, String close) {
        BigDecimal c = new BigDecimal(close);
        return new DailyBar(AAPL, LocalDate.parse(date), c, c, c, c, c, 100, null, null, null, null, false);
    }

    private static RehabFactor fwd(String exDate, String fwdA, String fwdB) {
        return new RehabFactor(AAPL, LocalDate.parse(exDate), new BigDecimal(fwdA), new BigDecimal(fwdB), BigDecimal.ONE,
                BigDecimal.ZERO, 64, null, null, 0, 0);
    }

    private static RehabFactor factor(String exDate, String fwdA, String bwdB) {
        return new RehabFactor(AAPL, LocalDate.parse(exDate), new BigDecimal(fwdA), BigDecimal.ZERO, BigDecimal.ONE,
                new BigDecimal(bwdB), 64, new BigDecimal(bwdB), null, 0, 0);
    }

    /** 实测数据：AAPL 2026-08-10 除息 0.27，fwdA=0.99913；除息前的 2026-08-07 富途前复权 313.06（不复权 313.33）。 */
    @Test
    void 前复权只对除权日之前的K线生效且结果与富途一致() {
        List<DailyBar> bars = List.of(bar("2026-08-07", "313.33"), bar("2026-08-10", "308.26"));
        List<RehabFactor> factors = List.of(factor("2026-05-11", "0.99907", "0.27"), factor("2026-08-10", "0.99913", "0.27"));

        List<DailyBar> out = BarAdjuster.adjust(bars, factors, Adjustment.FORWARD, FactorMode.CUMULATIVE);

        assertThat(out.get(0).close().setScale(2, java.math.RoundingMode.HALF_UP)).isEqualByComparingTo("313.06");
        assertThat(out.get(1).close()).isEqualByComparingTo("308.26");   // 除权日当天及之后不动
        assertThat(out.get(0).volume()).isEqualTo(100);                  // 成交量不复权
    }

    @Test
    void 累计模式取最近一条_逐事件模式复合两条() {
        List<DailyBar> bars = List.of(bar("2026-05-01", "100"));
        List<RehabFactor> factors = List.of(fwd("2026-05-11", "0.9", "1"), fwd("2026-08-10", "0.5", "2"));

        DailyBar cumulative = BarAdjuster.adjust(bars, factors, Adjustment.FORWARD, FactorMode.CUMULATIVE).get(0);
        DailyBar perEvent = BarAdjuster.adjust(bars, factors, Adjustment.FORWARD, FactorMode.PER_EVENT).get(0);

        assertThat(cumulative.close()).isEqualByComparingTo("91");                 // 只用 05-11 那条：100×0.9+1
        assertThat(perEvent.close()).isEqualByComparingTo("47.5");                 // 0.5×(0.9×100+1)+2 = 47.5
    }

    @Test
    void 后复权对除权日及之后生效() {
        List<DailyBar> bars = List.of(bar("2026-08-07", "100"), bar("2026-08-10", "100"));
        List<RehabFactor> factors = List.of(factor("2026-08-10", "0.99913", "0.27"));

        List<DailyBar> out = BarAdjuster.adjust(bars, factors, Adjustment.BACKWARD, FactorMode.CUMULATIVE);

        assertThat(out.get(0).close()).isEqualByComparingTo("100");
        assertThat(out.get(1).close()).isEqualByComparingTo("100.27");
    }

    @Test
    void 后复权逐事件从最近向最早复合() {
        // 2010 年 2:1 拆股（bwdA=2，bwdB=0），2020 年分红 1（bwdA=1，bwdB=1）；2021 年价格 100 的后复权 = 2×(1×100+1)+0 = 202
        RehabFactor split = new RehabFactor(AAPL, LocalDate.parse("2010-01-01"), BigDecimal.ONE, BigDecimal.ZERO, new BigDecimal("2"), BigDecimal.ZERO, 0, null, null, 2, 1);
        RehabFactor div = new RehabFactor(AAPL, LocalDate.parse("2020-01-01"), BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE, 64, BigDecimal.ONE, null, 0, 0);
        List<DailyBar> out = BarAdjuster.adjust(List.of(bar("2021-01-01", "100")), List.of(split, div), Adjustment.BACKWARD, FactorMode.PER_EVENT);
        assertThat(out.get(0).close()).isEqualByComparingTo("202");
    }

    @Test
    void 除权日当天那根的前收按前一日口径折算() {
        // 拆股 10:1 于 06-10：06-10 的收盘不动，但它的前收（06-07 收盘 1208.88）要折成 120.888
        RehabFactor split = new RehabFactor(AAPL, LocalDate.parse("2024-06-10"), new BigDecimal("0.1"), BigDecimal.ZERO, new BigDecimal("10"), BigDecimal.ZERO, 0, null, null, 10, 1);
        DailyBar splitDay = new DailyBar(AAPL, LocalDate.parse("2024-06-10"), new BigDecimal("120.37"), new BigDecimal("123"), new BigDecimal("117"),
                new BigDecimal("121.79"), new BigDecimal("1208.88"), 100, null, null, null, null, false);
        DailyBar out = BarAdjuster.adjust(List.of(splitDay), List.of(split), Adjustment.FORWARD, FactorMode.PER_EVENT).get(0);
        assertThat(out.close()).isEqualByComparingTo("121.79");
        assertThat(out.lastClose()).isEqualByComparingTo("120.888");
    }

    @Test
    void 无因子或不复权原样返回() {
        List<DailyBar> bars = List.of(bar("2026-08-07", "1"));
        assertThat(BarAdjuster.adjust(bars, List.of(), Adjustment.FORWARD, FactorMode.CUMULATIVE)).isSameAs(bars);
        assertThat(BarAdjuster.adjust(bars, List.of(factor("2026-08-10", "0.5", "0")), Adjustment.NONE, FactorMode.CUMULATIVE)).isSameAs(bars);
    }
}
