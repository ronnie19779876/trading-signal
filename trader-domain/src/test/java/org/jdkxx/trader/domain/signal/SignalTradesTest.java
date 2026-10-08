package org.jdkxx.trader.domain.signal;

import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.RehabFactor;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SignalTradesTest {

    private static final Instrument X = Instrument.us("X");
    private static final SentinelThresholds TH = SentinelThresholds.V1;
    private static final LocalDate SIGNAL = LocalDate.of(2026, 3, 2);

    private static DailyBar bar(LocalDate d, double o, double h, double l, double c) {
        return new DailyBar(X, d, BigDecimal.valueOf(o), BigDecimal.valueOf(h), BigDecimal.valueOf(l), BigDecimal.valueOf(c),
                null, 1000, null, null, null, null, false);
    }

    @Test
    void 持有期间拆股时价格按判定日口径连续() {
        List<DailyBar> raw = new ArrayList<>();
        Set<LocalDate> days = new HashSet<>();
        LocalDate d = SIGNAL.minusDays(30);
        for (; !d.isAfter(SIGNAL); d = d.plusDays(1)) {
            raw.add(bar(d, 100, 101, 99, 100));
            days.add(d);
        }
        // 次日 1 拆 2：原始价减半
        LocalDate splitDay = SIGNAL.plusDays(1);
        raw.add(bar(splitDay, 50, 50.5, 49.5, 50));
        raw.add(bar(splitDay.plusDays(1), 50, 50, 46, 47));      // 判定日口径 94 ≤ 止损 95
        days.add(splitDay);
        days.add(splitDay.plusDays(1));
        RehabFactor split = new RehabFactor(X, splitDay, new BigDecimal("0.5"), BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO,
                1, null, null, 1, 2);

        PaperTrade.Result r = SignalTrades.simulate(raw, List.of(split), days, SIGNAL, 100, 95, splitDay.plusDays(1), TH,
                PaperTrade.Rules.of(TH));

        assertThat(r.entry()).isCloseTo(100, within(1e-9));
        assertThat(r.stop()).isCloseTo(95, within(1e-9));
        assertThat(r.exit()).isCloseTo(94, within(1e-9));
        assertThat(r.reason()).isEqualTo(PaperTrade.ExitReason.STOP);
        assertThat(r.r()).isCloseTo(-6 / 5.0, within(1e-9));
    }

    /**
     * 要害：当前价必须和 entry 走同一条折回路径。持有期间拆股后原始价减半，
     * 若 lastClose 漏了 {@code / scale}，页面上就会显示"当前价 51、入场价 100、浮亏 −49%"这种假亏损。
     */
    @Test
    void 持有期间拆股时当前价也按判定日口径() {
        List<DailyBar> raw = new ArrayList<>();
        Set<LocalDate> days = new HashSet<>();
        LocalDate d = SIGNAL.minusDays(30);
        for (; !d.isAfter(SIGNAL); d = d.plusDays(1)) {
            raw.add(bar(d, 100, 101, 99, 100));
            days.add(d);
        }
        LocalDate splitDay = SIGNAL.plusDays(1);                 // 次日 1 拆 2：原始价减半
        LocalDate last = splitDay.plusDays(1);
        raw.add(bar(splitDay, 50, 50.5, 49.5, 50));              // 判定日口径 100
        raw.add(bar(last, 50.5, 51.5, 50, 51));                  // 判定日口径 102，在止损之上 → 未平仓
        days.add(splitDay);
        days.add(last);
        RehabFactor split = new RehabFactor(X, splitDay, new BigDecimal("0.5"), BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO,
                1, null, null, 1, 2);

        PaperTrade.Result r = SignalTrades.simulate(raw, List.of(split), days, SIGNAL, 100, 95, last, TH,
                PaperTrade.Rules.of(TH));

        assertThat(r.reason()).as("还没触止损，应为未平仓").isEqualTo(PaperTrade.ExitReason.OPEN);
        assertThat(r.entry()).as("入场价是判定日口径").isCloseTo(100, within(1e-9));
        assertThat(r.lastClose()).as("当前价必须同口径——漏了折回就会是 51").isCloseTo(102, within(1e-9));
        assertThat(r.lastCloseDate()).isEqualTo(last);
    }

    /** 停牌/缺 K 线时当前价会滞后：lastCloseDate 早于查询截止日，调用方必须把这个日期一起展示。 */
    @Test
    void 缺K线时当前价的日期早于截止日() {
        List<DailyBar> raw = new ArrayList<>();
        Set<LocalDate> days = new HashSet<>();
        for (LocalDate d = SIGNAL.minusDays(30); !d.isAfter(SIGNAL.plusDays(3)); d = d.plusDays(1)) {
            raw.add(bar(d, 100, 101, 99, 100));
            days.add(d);
        }
        LocalDate lastBar = SIGNAL.plusDays(3);
        LocalDate through = SIGNAL.plusDays(8);                  // 之后停牌，没有 K 线
        for (LocalDate d = lastBar.plusDays(1); !d.isAfter(through); d = d.plusDays(1)) {
            days.add(d);
        }

        PaperTrade.Result r = SignalTrades.simulate(raw, List.of(), days, SIGNAL, 100, 95, through, TH,
                PaperTrade.Rules.of(TH));

        assertThat(r.reason()).isEqualTo(PaperTrade.ExitReason.OPEN);
        assertThat(r.lastCloseDate()).as("最后一根 K 线").isEqualTo(lastBar);
        assertThat(r.lastCloseDate()).as("早于截止日，所以当前价是陈旧的").isBefore(through);
    }

    @Test
    void 查询截止日不同时同一信号的结果只随截止日变化() {
        List<DailyBar> raw = new ArrayList<>();
        Set<LocalDate> days = new HashSet<>();
        for (LocalDate d = SIGNAL.minusDays(700); !d.isAfter(SIGNAL.plusDays(10)); d = d.plusDays(1)) {
            double p = 100 + Math.sin(d.toEpochDay() / 7.0) * 3;
            raw.add(bar(d, p, p + 1, p - 1, p));
            days.add(d);
        }
        PaperTrade.Rules rules = PaperTrade.Rules.of(TH);
        double close = raw.stream().filter(b -> b.tradeDate().equals(SIGNAL)).findFirst().orElseThrow().close().doubleValue();

        PaperTrade.Result full = SignalTrades.simulate(raw, List.of(), days, SIGNAL, close, close - 5, SIGNAL.plusDays(10), TH, rules);
        PaperTrade.Result trimmed = SignalTrades.simulate(raw.subList(200, raw.size()), List.of(), days, SIGNAL, close, close - 5,
                SIGNAL.plusDays(10), TH, rules);

        assertThat(trimmed).isEqualTo(full);
    }

    @Test
    void 画图的K线折回判定日口径_判定日之后拆股也对齐() {
        LocalDate d0 = LocalDate.of(2026, 3, 2);
        LocalDate d1 = d0.plusDays(1);
        LocalDate d2 = d0.plusDays(2);
        List<DailyBar> raw = List.of(bar(d0, 100, 101, 99, 100), bar(d1, 50, 51, 49, 50), bar(d2, 52, 53, 51, 52));
        RehabFactor split = new RehabFactor(X, d1, new BigDecimal("0.5"), BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO,
                1, null, null, 1, 2);

        List<SignalBar> atSignal = SignalTrades.scaledTo(raw, List.of(split), Set.of(d0, d1, d2), d2, d0);
        List<SignalBar> atToday = SignalTrades.scaledTo(raw, List.of(split), Set.of(d0, d1, d2), d2, d2);

        assertThat(atSignal).extracting(SignalBar::close).containsExactly(100.0, 100.0, 104.0);
        assertThat(atSignal.get(1).volume()).isCloseTo(500, within(1e-9));
        assertThat(atToday).extracting(SignalBar::close).containsExactly(50.0, 50.0, 52.0);
    }

    @Test
    void 两种止损倍数() {
        assertThat(SignalTrades.stop(100, 2, null, 2.5, TH)).isEqualTo(95);
        assertThat(SignalTrades.stop(100, 2, 94.0, 2.5, TH)).isEqualTo(93);
    }
}
