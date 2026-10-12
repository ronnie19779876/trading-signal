package org.jdkxx.trader.domain.selection;

import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.RehabFactor;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class AdjustedPricesTest {

    private static final Instrument X = Instrument.us("X");
    private static final LocalDate D0 = LocalDate.of(2024, 6, 3);

    private static DailyBar bar(LocalDate d, double close) {
        BigDecimal c = BigDecimal.valueOf(close);
        return new DailyBar(X, d, c, c, c, c, null, 1, null, null, null, null, false);
    }

    private static RehabFactor split(LocalDate ex, int base, int ert) {
        return new RehabFactor(X, ex, BigDecimal.valueOf((double) base / ert), BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO,
                RehabFactor.ACT_SPLIT, null, null, base, ert);
    }

    /** 1 拆 10：除权前原始价 1000、后 100，调整后连续，收益不跳；不复权价仍是原值（判定 10 美元门槛用）。 */
    @Test
    void 拆股前后调整价连续_不复权价保留原值() {
        List<DailyBar> futu = List.of(bar(D0, 1000), bar(D0.plusDays(1), 1010), bar(D0.plusDays(2), 101), bar(D0.plusDays(3), 102));
        AdjustedPrices p = AdjustedPrices.build(futu, List.of(split(D0.plusDays(2), 1, 10)), List.of());

        int i = p.indexAt(D0.plusDays(2), 5);
        assertThat(p.returnOver(i, 1)).isCloseTo(0.0, within(1e-12));
        assertThat(p.rawClose(p.indexAt(D0, 5))).isEqualTo(1000.0);
        assertThat(p.returnOver(p.indexAt(D0.plusDays(3), 5), 3)).isCloseTo(102.0 / 100 - 1, within(1e-12));
    }

    /** 盈透段在第一个共同交易日按比值拼接；早期不复权价由调整价除以股数因子还原。 */
    @Test
    void 盈透段按接缝比值拼接并还原不复权价() {
        LocalDate seam = D0.plusDays(10);
        List<DailyBar> futu = List.of(bar(seam, 1000), bar(seam.plusDays(1), 1010), bar(seam.plusDays(2), 101));
        List<RehabFactor> f = List.of(split(seam.plusDays(2), 1, 10));
        // 盈透截至今天已按 1:10 调整：接缝日 100、更早 90
        List<SplitAdjustedBar> ib = List.of(new SplitAdjustedBar(D0, null, null, null, BigDecimal.valueOf(90), 0),
                new SplitAdjustedBar(seam, null, null, null, BigDecimal.valueOf(100), 0));
        AdjustedPrices p = AdjustedPrices.build(futu, f, ib);

        assertThat(p.firstDate()).isEqualTo(D0);
        int early = p.indexAt(D0, 5);
        assertThat(p.rawClose(early)).isCloseTo(900.0, within(1e-9));
        assertThat(p.returnOver(p.indexAt(seam, 5), 1)).isCloseTo(100.0 / 90 - 1, within(1e-12));
    }

    @Test
    void 拆股缺比例整条作废_不猜() {
        RehabFactor bad = new RehabFactor(X, D0, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO,
                RehabFactor.ACT_SPLIT, null, null, 0, 0);
        AdjustedPrices p = AdjustedPrices.build(List.of(bar(D0, 10)), List.of(bad), List.of());

        assertThat(p.valid()).isFalse();
        assertThat(p.problem()).contains("缺比例");
    }

    @Test
    void 太久没有收盘价算没有() {
        List<DailyBar> futu = new ArrayList<>(List.of(bar(D0, 10)));
        AdjustedPrices p = AdjustedPrices.build(futu, List.of(), List.of());

        assertThat(p.indexAt(D0.plusDays(5), 7)).isZero();
        assertThat(p.indexAt(D0.plusDays(30), 7)).isEqualTo(-1);
        assertThat(p.indexAt(D0.minusDays(1), 7)).isEqualTo(-1);
        assertThat(Double.isNaN(p.returnOver(0, 1))).isTrue();
    }
}
