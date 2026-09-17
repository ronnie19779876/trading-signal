package org.jdkxx.trader.domain.signal;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PaperTradeTest {

    private static final PaperTrade.Rules RULES = PaperTrade.Rules.of(SentinelThresholds.V1);

    /** 每根 {open, high, low, close}；判定日是第 0 根，收盘 100。 */
    private static List<SignalBar> bars(double[]... ohlc) {
        List<SignalBar> out = new ArrayList<>();
        LocalDate d = LocalDate.of(2026, 1, 5);
        for (double[] x : ohlc) {
            out.add(new SignalBar(d, x[0], x[1], x[2], x[3], 1000));
            d = d.plusDays(1);
        }
        return out;
    }

    private static double[] atr(int n, double value) {
        double[] a = new double[n];
        Arrays.fill(a, value);
        return a;
    }

    @Test
    void 次日开盘入场且收盘跌破止损离场() {
        List<SignalBar> b = bars(new double[]{99, 101, 98, 100}, new double[]{101, 102, 100, 101}, new double[]{100, 100, 94, 95});

        PaperTrade.Result r = PaperTrade.simulate(b, atr(3, 2), 0, 96, RULES);

        assertThat(r.entry()).isEqualTo(101);
        assertThat(r.reason()).isEqualTo(PaperTrade.ExitReason.STOP);
        assertThat(r.exit()).isEqualTo(95);
        assertThat(r.r()).isCloseTo((95 - 101) / 4.0, within(1e-12));
        assertThat(r.barsHeld()).isEqualTo(2);
    }

    @Test
    void 盘中跌破止损但收盘守住不离场() {
        List<SignalBar> b = bars(new double[]{99, 101, 98, 100}, new double[]{100, 101, 90, 97});

        PaperTrade.Result r = PaperTrade.simulate(b, atr(2, 2), 0, 96, RULES);

        assertThat(r.reason()).isEqualTo(PaperTrade.ExitReason.OPEN);
        assertThat(r.r()).isNull();
        assertThat(r.maeR()).isCloseTo((90 - 100) / 4.0, within(1e-12));
    }

    @Test
    void 二十个交易日未触及正1R则时间止损() {
        List<double[]> x = new ArrayList<>();
        x.add(new double[]{99, 101, 98, 100});
        for (int i = 0; i < 25; i++) {
            x.add(new double[]{100, 102, 99, 101});
        }
        List<SignalBar> b = bars(x.toArray(double[][]::new));

        PaperTrade.Result r = PaperTrade.simulate(b, atr(b.size(), 2), 0, 96, RULES);

        assertThat(r.reason()).isEqualTo(PaperTrade.ExitReason.TIME);
        assertThat(r.exitDate()).isEqualTo(b.get(21).date());      // 入场日（第 1 根）算第 0 天
    }

    @Test
    void 触及正1R后吊灯止损生效且不再有时间止损() {
        List<double[]> x = new ArrayList<>();
        x.add(new double[]{99, 101, 98, 100});
        x.add(new double[]{100, 105, 100, 104});      // 触及 +1R = 104
        for (int i = 0; i < 25; i++) {
            x.add(new double[]{104, 106, 103, 105});
        }
        x.add(new double[]{104, 104, 97, 98});        // 吊灯 = 106 − 3×2 = 100，收盘 98 跌破
        List<SignalBar> b = bars(x.toArray(double[][]::new));

        PaperTrade.Result r = PaperTrade.simulate(b, atr(b.size(), 2), 0, 96, RULES);

        assertThat(r.touchedPlusOneR()).isTrue();
        assertThat(r.reason()).isEqualTo(PaperTrade.ExitReason.CHANDELIER);
        assertThat(r.exit()).isEqualTo(98);
    }

    @Test
    void 减半仓变体按正1R成交一半() {
        List<SignalBar> b = bars(new double[]{99, 101, 98, 100}, new double[]{100, 105, 100, 104},
                new double[]{104, 104, 90, 91});

        PaperTrade.Result half = PaperTrade.simulate(b, atr(3, 2), 0, 96, RULES.withHalfAtPlusOneR());
        PaperTrade.Result full = PaperTrade.simulate(b, atr(3, 2), 0, 96, RULES);

        assertThat(half.r()).isCloseTo((0.5 * (104 - 100) + 0.5 * (91 - 100)) / 4, within(1e-12));
        assertThat(full.r()).isCloseTo((91 - 100) / 4.0, within(1e-12));
    }

    @Test
    void 判定日之后没有K线返回空() {
        assertThat(PaperTrade.simulate(bars(new double[]{99, 101, 98, 100}), atr(1, 2), 0, 96, RULES)).isNull();
    }
}
