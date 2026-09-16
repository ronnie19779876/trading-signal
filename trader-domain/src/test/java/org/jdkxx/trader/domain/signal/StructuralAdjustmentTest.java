package org.jdkxx.trader.domain.signal;

import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.RehabFactor;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** 因子取值均来自 2026-09-17 FutuShareActionsIT 实测。 */
class StructuralAdjustmentTest {

    private static final Instrument X = Instrument.us("X");

    private static DailyBar bar(String date, String close, long volume) {
        BigDecimal c = new BigDecimal(close);
        return new DailyBar(X, LocalDate.parse(date), c, c, c, c, null, volume, null, null, null, null, false);
    }

    private static RehabFactor event(String exDate, String fwdA, long flag, int[] split, int[] join, int[] bonus) {
        return new RehabFactor(X, LocalDate.parse(exDate), new BigDecimal(fwdA), BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO,
                flag, null, null, split[0], split[1], join[0], join[1], bonus[0], bonus[1], 0, 0);
    }

    private static final int[] NONE = {0, 0};

    @Test
    void 拆股前的价除以比例且量乘以比例() {
        // NVDA 2024-06-10 拆股 1:10
        RehabFactor split = event("2024-06-10", "0.1", 1, new int[]{1, 10}, NONE, NONE);

        StructuralAdjustment.Result r = StructuralAdjustment.apply(
                List.of(bar("2024-06-07", "1208.88", 41_238_580), bar("2024-06-10", "121.79", 314_162_656)),
                List.of(split), LocalDate.parse("2024-06-10"));

        assertThat(r.ok()).isTrue();
        assertThat(r.bars().get(0).close()).isCloseTo(120.888, within(1e-9));
        assertThat(r.bars().get(0).volume()).isCloseTo(412_385_800, within(1e-3));
        assertThat(r.bars().get(1).close()).isEqualTo(121.79);
    }

    @Test
    void 纯拆股用精确比例而不是五位小数的因子() {
        // WMT 2024-02-26 拆股 1:3，富途 fwdA=0.33333
        RehabFactor split = event("2024-02-26", "0.33333", 1, new int[]{1, 3}, NONE, NONE);

        SignalBar before = StructuralAdjustment.apply(List.of(bar("2024-02-23", "180", 3)),
                List.of(split), LocalDate.parse("2024-02-26")).bars().get(0);

        assertThat(before.close()).isEqualTo(180 / 3.0);
        assertThat(before.volume()).isEqualTo(9.0);
    }

    @Test
    void 分拆只调价不调量() {
        // DD 2025-11-03 分拆，fwdA=0.41825
        RehabFactor spinOff = event("2025-11-03", "0.41825", 256, NONE, NONE, NONE);

        SignalBar before = StructuralAdjustment.apply(List.of(bar("2025-10-31", "81.65", 4_005_314)),
                List.of(spinOff), LocalDate.parse("2025-11-03")).bars().get(0);

        assertThat(before.close()).isCloseTo(81.65 * 0.41825, within(1e-9));
        assertThat(before.volume()).isEqualTo(4_005_314);
    }

    @Test
    void 合股与分拆同一事件时量只按合股比例() {
        // HON 2026-06-29 flag=258，合股 2:1，fwdA=1.09203
        RehabFactor mixed = event("2026-06-29", "1.09203", 258, NONE, new int[]{2, 1}, NONE);

        SignalBar before = StructuralAdjustment.apply(List.of(bar("2026-06-26", "100", 1_000_000)),
                List.of(mixed), LocalDate.parse("2026-06-29")).bars().get(0);

        assertThat(before.close()).isCloseTo(109.203, within(1e-9));
        assertThat(before.volume()).isCloseTo(500_000, within(1e-6));
    }

    @Test
    void 送股按每base股送ert股调量() {
        // TSM 2002-06-19 送股 10:1，fwdA=0.90909
        RehabFactor bonus = event("2002-06-19", "0.90909", 4, NONE, NONE, new int[]{10, 1});

        SignalBar before = StructuralAdjustment.apply(List.of(bar("2002-06-18", "10", 1_000)),
                List.of(bonus), LocalDate.parse("2002-06-19")).bars().get(0);

        assertThat(before.volume()).isCloseTo(1_100, within(1e-9));
    }

    @Test
    void 普通分红不调() {
        RehabFactor dividend = event("2026-08-10", "0.99913", 64, NONE, NONE, NONE);

        SignalBar before = StructuralAdjustment.apply(List.of(bar("2026-08-07", "313.33", 1)),
                List.of(dividend), LocalDate.parse("2026-08-10")).bars().get(0);

        assertThat(before.close()).isEqualTo(313.33);
    }

    /** 守护：判定日之后才除权的事件是未来信息，不能改变判定窗口里的价量。 */
    @Test
    void 判定日之后的事件不生效() {
        RehabFactor split = event("2024-06-10", "0.1", 1, new int[]{1, 10}, NONE, NONE);

        StructuralAdjustment.Result r = StructuralAdjustment.apply(
                List.of(bar("2024-06-06", "1209.98", 10), bar("2024-06-07", "1208.88", 10), bar("2024-06-10", "121.79", 10)),
                List.of(split), LocalDate.parse("2024-06-07"));

        assertThat(r.bars()).hasSize(2);
        assertThat(r.bars().get(1).close()).isEqualTo(1208.88);
        assertThat(r.bars().get(1).volume()).isEqualTo(10);
    }

    @Test
    void 股数变动缺比例时不猜() {
        RehabFactor join = event("2026-06-24", "3", 2, NONE, NONE, NONE);

        StructuralAdjustment.Result r = StructuralAdjustment.apply(List.of(bar("2026-06-23", "30", 1)),
                List.of(join), LocalDate.parse("2026-06-24"));

        assertThat(r.ok()).isFalse();
        assertThat(r.problem()).contains("缺比例");
    }

    @Test
    void 早于最早K线的事件不看_缺比例也不拦() {
        // ISRG 2003-07-01 合股（V10 之前入库、比例为 0），K 线从 2006 年开始
        RehabFactor oldJoin = event("2003-07-01", "2", 2, NONE, NONE, NONE);

        StructuralAdjustment.Result r = StructuralAdjustment.apply(List.of(bar("2006-08-21", "30", 1)),
                List.of(oldJoin), LocalDate.parse("2006-08-21"));

        assertThat(r.ok()).isTrue();
        assertThat(r.bars().get(0).close()).isEqualTo(30.0);
    }

    @Test
    void 空K剔除() {
        DailyBar blank = new DailyBar(X, LocalDate.parse("2026-01-02"), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, null, 0, null, null, null, null, true);

        assertThat(StructuralAdjustment.apply(List.of(blank, bar("2026-01-05", "2", 5)), List.of(), LocalDate.parse("2026-01-05"))
                .bars()).extracting(SignalBar::date).containsExactly(LocalDate.parse("2026-01-05"));
    }
}
