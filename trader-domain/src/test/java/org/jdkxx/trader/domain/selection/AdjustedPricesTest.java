package org.jdkxx.trader.domain.selection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.RehabFactor;
import org.junit.jupiter.api.Test;

class AdjustedPricesTest {

  private static final Instrument X = Instrument.us("X");
  private static final LocalDate D0 = LocalDate.of(2024, 6, 3);

  private static DailyBar bar(LocalDate d, double close) {
    BigDecimal c = BigDecimal.valueOf(close);
    return new DailyBar(X, d, c, c, c, c, null, 1, null, null, null, null, false);
  }

  private static RehabFactor split(LocalDate ex, int base, int ert) {
    return new RehabFactor(
        X,
        ex,
        BigDecimal.valueOf((double) base / ert),
        BigDecimal.ZERO,
        BigDecimal.ONE,
        BigDecimal.ZERO,
        RehabFactor.ACT_SPLIT,
        null,
        null,
        base,
        ert);
  }

  /**
   * 1-for-10 split: raw price 1000 before the ex-date and 100 after; adjusted prices are continuous
   * with no jump in returns; the unadjusted price keeps its original value (for the 10 USD
   * threshold).
   */
  @Test
  void 拆股前后调整价连续_不复权价保留原值() {
    List<DailyBar> futu =
        List.of(
            bar(D0, 1000),
            bar(D0.plusDays(1), 1010),
            bar(D0.plusDays(2), 101),
            bar(D0.plusDays(3), 102));
    AdjustedPrices p = AdjustedPrices.build(futu, List.of(split(D0.plusDays(2), 1, 10)), List.of());

    int i = p.indexAt(D0.plusDays(2), 5);
    assertThat(p.returnOver(i, 1)).isCloseTo(0.0, within(1e-12));
    assertThat(p.rawClose(p.indexAt(D0, 5))).isEqualTo(1000.0);
    assertThat(p.returnOver(p.indexAt(D0.plusDays(3), 5), 3))
        .isCloseTo(102.0 / 100 - 1, within(1e-12));
  }

  /**
   * The IBKR segment is spliced by the price ratio on the first common trading day; its early
   * unadjusted prices are recovered by dividing the adjusted price by the share-count factor.
   */
  @Test
  void 盈透段按接缝比值拼接并还原不复权价() {
    LocalDate seam = D0.plusDays(10);
    List<DailyBar> futu =
        List.of(bar(seam, 1000), bar(seam.plusDays(1), 1010), bar(seam.plusDays(2), 101));
    List<RehabFactor> f = List.of(split(seam.plusDays(2), 1, 10));
    // IBKR is already adjusted for the 1:10 split as of today: 100 on the seam day, 90 earlier
    List<SplitAdjustedBar> ib =
        List.of(
            new SplitAdjustedBar(D0, null, null, null, BigDecimal.valueOf(90), 0),
            new SplitAdjustedBar(seam, null, null, null, BigDecimal.valueOf(100), 0));
    AdjustedPrices p = AdjustedPrices.build(futu, f, ib);

    assertThat(p.firstDate()).isEqualTo(D0);
    int early = p.indexAt(D0, 5);
    assertThat(p.rawClose(early)).isCloseTo(900.0, within(1e-9));
    assertThat(p.returnOver(p.indexAt(seam, 5), 1)).isCloseTo(100.0 / 90 - 1, within(1e-12));
  }

  @Test
  void 拆股缺比例整条作废_不猜() {
    RehabFactor bad =
        new RehabFactor(
            X,
            D0,
            BigDecimal.ONE,
            BigDecimal.ZERO,
            BigDecimal.ONE,
            BigDecimal.ZERO,
            RehabFactor.ACT_SPLIT,
            null,
            null,
            0,
            0);
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

  private static RehabFactor dividend(LocalDate ex, double cash, long flag, int base, int ert) {
    return new RehabFactor(
        X,
        ex,
        BigDecimal.ONE,
        BigDecimal.ZERO,
        BigDecimal.ONE,
        BigDecimal.ZERO,
        flag,
        BigDecimal.valueOf(cash),
        null,
        base,
        ert);
  }

  /**
   * Ex-dividend 2 on a 100 stock that opens the ex-date at 98: total return 0, price return -2%.
   */
  @Test
  void 总收益把现金分红再投入() {
    List<DailyBar> futu = List.of(bar(D0, 100), bar(D0.plusDays(1), 98), bar(D0.plusDays(2), 99));
    AdjustedPrices p =
        AdjustedPrices.build(
            futu, List.of(dividend(D0.plusDays(1), 2, RehabFactor.ACT_DIVIDEND, 0, 0)), List.of());

    assertThat(p.priceReturn(0, 1)).isCloseTo(-0.02, within(1e-12));
    assertThat(p.totalReturn(0, 1)).isCloseTo(0.0, within(1e-12));
    assertThat(p.totalReturn(0, 2)).isCloseTo(99.0 / 98 - 1, within(1e-12));
  }

  /**
   * Split 1:2 and a 1.00 dividend (post-split basis) on the same day: 100 → 49.5 is a 1% total gain
   * ((49.5 + 1) ÷ 50).
   */
  @Test
  void 同日拆股与分红按拆后口径() {
    List<DailyBar> futu = List.of(bar(D0, 100), bar(D0.plusDays(1), 49.5));
    AdjustedPrices p =
        AdjustedPrices.build(
            futu,
            List.of(
                dividend(
                    D0.plusDays(1), 1, RehabFactor.ACT_SPLIT | RehabFactor.ACT_DIVIDEND, 1, 2)),
            List.of());

    assertThat(p.priceReturn(0, 1)).isCloseTo(-0.01, within(1e-12));
    assertThat(p.totalReturn(0, 1)).isCloseTo(0.01, within(1e-12));
  }

  @Test
  void 跳过最近几根的动能窗口() {
    List<DailyBar> futu = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      futu.add(bar(D0.plusDays(i), 100 + 10 * i));
    }
    AdjustedPrices p = AdjustedPrices.build(futu, List.of(), List.of());

    assertThat(p.returnBetween(4, 4, 1)).isCloseTo(130.0 / 100 - 1, within(1e-12));
    assertThat(p.returnBetween(4, 4, 0)).isEqualTo(p.returnOver(4, 4));
    assertThat(Double.isNaN(p.returnBetween(4, 5, 1))).isTrue();
  }
}
