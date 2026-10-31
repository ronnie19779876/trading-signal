package org.jdkxx.trader.domain.selection;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PriceSeamTest {

  private static final LocalDate D0 = LocalDate.of(2022, 9, 8);
  private static final PriceSeam.Thresholds T = new PriceSeam.Thresholds(5, 0.001, 0.95);

  private static SplitAdjustedBar ib(LocalDate d, double close) {
    BigDecimal c = BigDecimal.valueOf(close);
    return new SplitAdjustedBar(d, c, c, c, c, 0);
  }

  /**
   * IBKR price = Futu price ÷ 4 (a constant split-adjustment factor), so daily returns are
   * identical.
   */
  @Test
  void 只差常数倍时通过() {
    List<SplitAdjustedBar> ibkr = new ArrayList<>();
    Map<LocalDate, BigDecimal> futu = new HashMap<>();
    for (int i = 0; i < 10; i++) {
      double raw = 100 + i * 1.7;
      ibkr.add(ib(D0.plusDays(i), raw / 4));
      futu.put(D0.plusDays(i), BigDecimal.valueOf(raw));
    }

    PriceSeam.Result r = PriceSeam.check(ibkr, futu, Set.of(), T);

    assertThat(r.ok()).isTrue();
    assertThat(r.overlapDays()).isEqualTo(10);
    assertThat(r.comparedDays()).isEqualTo(9);
    assertThat(r.withinDays()).isEqualTo(9);
    assertThat(r.maxAbsDiff()).isEqualByComparingTo("0");
  }

  /**
   * On the split day Futu's unadjusted price gaps while IBKR is adjusted: that pair is skipped, not
   * counted as a mismatch.
   */
  @Test
  void 股数变动当天跳过() {
    List<SplitAdjustedBar> ibkr = new ArrayList<>();
    Map<LocalDate, BigDecimal> futu = new HashMap<>();
    for (int i = 0; i < 10; i++) {
      double adjusted = 100 + i;
      ibkr.add(ib(D0.plusDays(i), adjusted));
      futu.put(
          D0.plusDays(i),
          BigDecimal.valueOf(i < 5 ? adjusted * 2 : adjusted)); // 1-for-2 split on day 5
    }

    PriceSeam.Result r = PriceSeam.check(ibkr, futu, Set.of(D0.plusDays(5)), T);

    assertThat(r.ok()).isTrue();
    assertThat(r.comparedDays()).isEqualTo(8);
    assertThat(r.detail()).contains("跳过股数变动 1 对");
  }

  @Test
  void 没登记的跳空判不通过() {
    List<SplitAdjustedBar> ibkr = new ArrayList<>();
    Map<LocalDate, BigDecimal> futu = new HashMap<>();
    for (int i = 0; i < 10; i++) {
      double adjusted = 100 + i;
      ibkr.add(ib(D0.plusDays(i), adjusted));
      futu.put(D0.plusDays(i), BigDecimal.valueOf(i < 5 ? adjusted * 2 : adjusted));
    }

    PriceSeam.Result r =
        PriceSeam.check(ibkr, futu, Set.of(), new PriceSeam.Thresholds(5, 0.001, 1.0));

    assertThat(r.ok()).isFalse();
    assertThat(r.maxAbsDiff().doubleValue()).isGreaterThan(0.4);
  }

  /**
   * Days missing on one side do not misalign intervals: only consecutive days present on both sides
   * are compared.
   */
  @Test
  void 只比两边都有的日子() {
    List<SplitAdjustedBar> ibkr = new ArrayList<>();
    Map<LocalDate, BigDecimal> futu = new HashMap<>();
    for (int i = 0; i < 10; i++) {
      double p = 100 + i * i;
      ibkr.add(ib(D0.plusDays(i), p));
      if (i != 4) {
        futu.put(D0.plusDays(i), BigDecimal.valueOf(p));
      }
    }

    PriceSeam.Result r = PriceSeam.check(ibkr, futu, Set.of(), T);

    assertThat(r.overlapDays()).isEqualTo(9);
    assertThat(r.comparedDays()).isEqualTo(8);
    assertThat(r.ok()).isTrue();
  }

  @Test
  void 可比较的对数不够不下通过结论() {
    PriceSeam.Result r =
        PriceSeam.check(
            List.of(ib(D0, 1), ib(D0.plusDays(1), 2)),
            Map.of(D0, BigDecimal.ONE, D0.plusDays(1), BigDecimal.TWO),
            Set.of(),
            T);

    assertThat(r.ok()).isFalse();
    assertThat(r.detail()).contains("不足 5 对");
  }

  @Test
  void 没有重叠时最大差为空() {
    PriceSeam.Result r = PriceSeam.check(List.of(ib(D0, 1)), Map.of(), Set.of(), T);

    assertThat(r.overlapDays()).isZero();
    assertThat(r.maxAbsDiff()).isNull();
    assertThat(r.ok()).isFalse();
  }
}
