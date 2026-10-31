package org.jdkxx.trader.core.selection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.jdkxx.trader.domain.selection.Exclusion;
import org.jdkxx.trader.domain.selection.Metric;
import org.jdkxx.trader.domain.selection.StockInputs;
import org.junit.jupiter.api.Test;

class SelectionBacktestServiceTest {

  private static final LocalDate CUTOFF = LocalDate.of(2026, 9, 23);

  private final SelectionBacktestService service =
      new SelectionBacktestService(null, null, null, null, null, () -> CUTOFF, null);

  @Test
  void 训练段不得触及留出段_最终评估必须触及() {
    assertThat(service.validate("F1A", LocalDate.of(2018, 1, 2), LocalDate.of(2022, 12, 30), false))
        .extracting(s -> s.version())
        .isEqualTo("F1A");
    assertThatThrownBy(
            () -> service.validate("F1", LocalDate.of(2018, 1, 2), LocalDate.of(2023, 1, 3), false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("final=true");
    assertThatThrownBy(
            () -> service.validate("F1", LocalDate.of(2018, 1, 2), LocalDate.of(2022, 6, 30), true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不触及留出段");
    assertThat(service.validate("F1", LocalDate.of(2018, 1, 2), CUTOFF, true)).isNotNull();
  }

  @Test
  void 规格必须预登记_区间有边界() {
    assertThatThrownBy(() -> service.validate("F9", LocalDate.of(2018, 1, 2), CUTOFF, true))
        .hasMessageContaining("没有预登记的规格");
    assertThatThrownBy(
            () ->
                service.validate("F1", LocalDate.of(2017, 12, 29), LocalDate.of(2020, 1, 2), false))
        .hasMessageContaining("不能早于");
    assertThatThrownBy(
            () -> service.validate("F1", LocalDate.of(2024, 1, 2), CUTOFF.plusDays(1), true))
        .hasMessageContaining("已收盘落定");
  }

  /** Added after t, or no date at all: excluded; already excluded rows keep their own reason. */
  @Test
  void 时点成分股口径按加入日期排除() {
    LocalDate t = LocalDate.of(2020, 6, 1);
    List<StockInputs> in =
        List.of(
            stock(1, null), stock(2, null), stock(3, null), stock(4, Exclusion.PRICE_BELOW_MIN));
    Map<Long, LocalDate> added =
        Map.of(
            1L,
            LocalDate.of(1990, 1, 1),
            2L,
            LocalDate.of(2020, 6, 2),
            4L,
            LocalDate.of(2024, 1, 1));

    List<StockInputs> out = SelectionBacktestService.pointInTime(in, added, t);

    assertThat(out.get(0).exclusion()).isNull();
    assertThat(out.get(1).exclusion()).isEqualTo(Exclusion.NOT_INDEX_MEMBER);
    assertThat(out.get(2).exclusion()).isEqualTo(Exclusion.NOT_INDEX_MEMBER);
    assertThat(out.get(2).exclusionDetail()).contains("没有指数加入日期");
    assertThat(out.get(3).exclusion()).isEqualTo(Exclusion.PRICE_BELOW_MIN);
  }

  private static StockInputs stock(long id, Exclusion ex) {
    return new StockInputs(
        id, "S" + id, "IT", ex, null, Map.of(Metric.ROE, 0.1), EnumSet.allOf(Metric.class));
  }
}
