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
    void 两种止损倍数() {
        assertThat(SignalTrades.stop(100, 2, null, 2.5, TH)).isEqualTo(95);
        assertThat(SignalTrades.stop(100, 2, 94.0, 2.5, TH)).isEqualTo(93);
    }
}
