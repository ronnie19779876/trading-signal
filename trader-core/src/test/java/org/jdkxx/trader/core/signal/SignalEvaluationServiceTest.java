package org.jdkxx.trader.core.signal;

import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.PoolRole;
import org.jdkxx.trader.domain.signal.SignalSuppression.PreviousDay;
import org.jdkxx.trader.storage.signal.SignalEvaluationRow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SignalEvaluationServiceTest {

    private static final Instrument X = Instrument.us("X");
    private static final LocalDate PREV = LocalDate.of(2026, 9, 1);

    private final SignalEvaluationService service =
            new SignalEvaluationService(null, null, null, null, null, null, null, null, null, null);

    private static SignalEvaluationRow stored(String status, String gates, String outcome) {
        return new SignalEvaluationRow(1, "X", PREV, "sentinel-v1", status, null, outcome, gates, 0, null, "POOL",
                null, null, null, null, null, null, "f", null, null, null);
    }

    @Test
    void 有正常判定的评估行时按它判断上一交易日() {
        assertThat(service.previousDay(stored("EVALUATED", "PPPP", "SIGNAL"), List.of(), List.of(), Set(), PREV))
                .isEqualTo(PreviousDay.PASSED);
        assertThat(service.previousDay(stored("EVALUATED", "PPPP", "SUPPRESSED_COOLDOWN"), List.of(), List.of(), Set(), PREV))
                .isEqualTo(PreviousDay.PASSED);
        assertThat(service.previousDay(stored("EVALUATED", "PPPP", "BLOCKED_BY_AI"), List.of(), List.of(), Set(), PREV))
                .isEqualTo(PreviousDay.BLOCKED_BY_AI);
        assertThat(service.previousDay(stored("EVALUATED", "PFPP", "NO_SIGNAL"), List.of(), List.of(), Set(), PREV))
                .isEqualTo(PreviousDay.NOT_PASSED);
    }

    /** 守护：上一交易日没有评估行（刚上线、漏跑）或当天不予判定时，不能直接当成"没过"——那会让连续满足的第二天重复发信号。 */
    @Test
    void 没有评估行或当天不予判定时现场重算() {
        // 合成行情：先涨后围绕 155 摆动形成支撑区，最后一根回踩支撑区放量收阳 → 上一交易日四门全过
        List<LocalDate> days = new ArrayList<>();
        List<DailyBar> raw = new ArrayList<>();
        LocalDate d = LocalDate.of(2025, 6, 2);
        for (int i = 0; i < 300; i++) {
            while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
                d = d.plusDays(1);
            }
            boolean last = i == 299;
            double p = i < 200 ? 100 + 0.25 * i : 155 + 5 * Math.sin(2 * Math.PI * (i - 200) / 20.0);
            double open = last ? 149.5 : p;
            double close = last ? 152 : p;
            double low = last ? 148.5 : p - 1;
            double high = last ? 152.5 : p + 1;
            raw.add(new DailyBar(X, d, bd(open), bd(high), bd(low), bd(close), null, last ? 3_000_000 : 1_000_000,
                    null, null, null, null, false));
            days.add(d);
            d = d.plusDays(1);
        }
        LocalDate prev = days.get(days.size() - 1);
        HashSet<LocalDate> calendar = new HashSet<>(days);

        assertThat(service.previousDay(null, raw, List.of(), calendar, prev)).isEqualTo(PreviousDay.PASSED);
        assertThat(service.previousDay(stored("SKIPPED_STALE_DATA", null, null), raw, List.of(), calendar, prev))
                .isEqualTo(PreviousDay.PASSED);
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v);
    }

    @Test
    void 冷却按交易日数计() {
        List<LocalDate> cal = List.of(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 3),
                LocalDate.of(2026, 9, 4), LocalDate.of(2026, 9, 8));
        assertThat(SignalEvaluationService.tradingDaysSince(cal, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 8))).isEqualTo(4);
        assertThat(SignalEvaluationService.tradingDaysSince(cal, null, LocalDate.of(2026, 9, 8))).isNull();
        assertThat(SignalEvaluationService.tradingDaysSince(cal, LocalDate.of(2020, 1, 2), LocalDate.of(2026, 9, 8))).isNull();
    }

    @Test
    void 角色只分池持仓与池外() {
        assertThat(SignalEvaluationService.roleOf(PoolRole.HOLDING)).isEqualTo("HOLDING");
        assertThat(SignalEvaluationService.roleOf(PoolRole.BENCHMARK)).isEqualTo("UNIVERSE");
        assertThat(SignalEvaluationService.roleOf(null)).isEqualTo("UNIVERSE");
    }

    private static java.util.Set<LocalDate> Set() {
        return java.util.Set.of();
    }
}
