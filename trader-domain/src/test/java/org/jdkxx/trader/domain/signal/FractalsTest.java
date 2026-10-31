package org.jdkxx.trader.domain.signal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class FractalsTest {

  @Test
  void 分形低点严格低于左右各两根() {
    double[] low = {5, 4, 3, 4, 5, 3, 3, 4, 5};

    // index 2 is a fractal; indexes 5 and 6 are equal, so neither is strictly lower than its
    // neighbour
    assertThat(Fractals.lows(low, 2, 0)).containsExactly(2);
  }

  @Test
  void 最后两根不可能成为分形() {
    double[] low = {5, 4, 3, 2, 1};

    assertThat(Fractals.lows(low, 2, 0)).isEmpty();
    assertThat(Fractals.lows(new double[] {5, 4, 3, 4, 5, 4, 1}, 2, 0)).containsExactly(2);
  }

  @Test
  void 只返回回看起点之后的分形() {
    double[] low = {5, 4, 3, 4, 5, 4, 2, 4, 5};

    assertThat(Fractals.lows(low, 2, 0)).containsExactly(2, 6);
    assertThat(Fractals.lows(low, 2, 3)).containsExactly(6);
  }

  @Test
  void 单链接聚类且丢弃触及不足的簇() {
    double[] price = {10, 20.3, 10.9, 20, 10.4, 30};
    LocalDate d = LocalDate.of(2026, 1, 1);
    LocalDate[] dates = {
      d, d.plusDays(1), d.plusDays(2), d.plusDays(3), d.plusDays(4), d.plusDays(5)
    };

    List<PriceZone> zones = Fractals.cluster(List.of(0, 1, 2, 3, 4, 5), price, dates, 0.5, 2);

    assertThat(zones).hasSize(2);
    assertThat(zones.get(0).bottom()).isEqualTo(10);
    assertThat(zones.get(0).top()).isEqualTo(10.9);
    assertThat(zones.get(0).touches()).isEqualTo(3);
    assertThat(zones.get(0).members()).containsExactly(dates[0], dates[2], dates[4]);
    assertThat(zones.get(1).bottom()).isEqualTo(20);
    assertThat(zones.get(1).top()).isEqualTo(20.3);
  }
}
