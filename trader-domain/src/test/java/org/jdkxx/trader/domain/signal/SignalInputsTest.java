package org.jdkxx.trader.domain.signal;

import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.Instrument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SignalInputsTest {

    private static final Instrument X = Instrument.us("X");
    private static final SentinelThresholds TH = SentinelThresholds.V1;
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 16);

    /** 判定日往前 500 天的工作日当作交易日历。 */
    private static List<LocalDate> calendar() {
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate d = AS_OF.minusDays(500); !d.isAfter(AS_OF); d = d.plusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                days.add(d);
            }
        }
        return days;
    }

    private static DailyBar bar(LocalDate d, boolean blank) {
        BigDecimal p = BigDecimal.valueOf(100 + (d.getDayOfYear() % 7));
        return new DailyBar(X, d, p, p.add(BigDecimal.ONE), p.subtract(BigDecimal.ONE), p, null, 1000, null, null, null, null, blank);
    }

    private static List<DailyBar> bars(List<LocalDate> days) {
        return new ArrayList<>(days.stream().map(d -> bar(d, false)).toList());
    }

    @Test
    void 日历之外的K线被剔除() {
        List<LocalDate> days = calendar();
        List<DailyBar> raw = bars(days);
        LocalDate saturday = AS_OF.minusDays(4);
        raw.add(bar(saturday, false));
        raw.sort(java.util.Comparator.comparing(DailyBar::tradeDate));

        SignalInputs.Prepared p = SignalInputs.prepare(raw, List.of(), days, AS_OF, TH);

        assertThat(p.skipped()).isNull();
        assertThat(p.droppedNonTradingDays()).containsExactly(saturday);
        assertThat(p.bars()).extracting(SignalBar::date).doesNotContain(saturday);
    }

    @Test
    void 缺交易日超过容忍度不予判定且停牌空K也算缺() {
        List<LocalDate> days = calendar();
        List<DailyBar> raw = bars(days);
        raw.remove(100);
        raw.remove(100);
        raw.remove(100);
        assertThat(SignalInputs.prepare(raw, List.of(), days, AS_OF, TH).skipped()).isNull();

        raw.set(200, bar(raw.get(200).tradeDate(), true));
        SignalInputs.Prepared p = SignalInputs.prepare(raw, List.of(), days, AS_OF, TH);

        assertThat(p.skipped().status()).isEqualTo(SentinelEvaluation.Status.SKIPPED_DATA_GAP);
        assertThat(p.missingTradingDays()).hasSize(4);
    }

    @Test
    void 上市前的日子不算缺() {
        List<LocalDate> days = calendar();
        List<DailyBar> raw = bars(days.subList(200, days.size()));

        SignalInputs.Prepared p = SignalInputs.prepare(raw, List.of(), days, AS_OF, TH);

        assertThat(p.missingTradingDays()).isEmpty();
    }

    @Test
    void 判定日没有K线判为陈旧() {
        List<LocalDate> days = calendar();
        List<DailyBar> raw = bars(days.subList(0, days.size() - 1));

        assertThat(SignalInputs.judge(raw, List.of(), days, AS_OF, TH).status())
                .isEqualTo(SentinelEvaluation.Status.SKIPPED_STALE_DATA);
    }

    @Test
    void 判定日不是交易日直接报错() {
        List<LocalDate> days = calendar();

        assertThatThrownBy(() -> SignalInputs.prepare(bars(days), List.of(), days, AS_OF.plusDays(4), TH))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 数据齐全时进入四门判定() {
        List<LocalDate> days = calendar();

        SentinelEvaluation e = SignalInputs.judge(bars(days), List.of(), days, AS_OF, TH);

        assertThat(e.status()).isEqualTo(SentinelEvaluation.Status.EVALUATED);
        assertThat(e.gates()).hasSize(4);
    }
}
