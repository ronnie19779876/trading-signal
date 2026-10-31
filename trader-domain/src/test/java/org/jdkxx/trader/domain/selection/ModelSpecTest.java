package org.jdkxx.trader.domain.selection;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModelSpecTest {

  private static StockInputs stock(long id, String sector, double base, double momentum) {
    Map<Metric, Double> m = new EnumMap<>(Metric.class);
    for (Metric x : Metric.values()) {
      m.put(x, x.factor() == Factor.MOMENTUM ? momentum : base);
    }
    return new StockInputs(id, "S" + id, sector, null, null, m, EnumSet.allOf(Metric.class));
  }

  private static Rating rating(List<StockScore> r, long id) {
    return r.stream().filter(s -> s.instrumentId() == id).findFirst().orElseThrow().rating();
  }

  @Test
  void F1规格与原常量逐项相同() {
    ModelSpec f1 = ModelSpec.F1;
    assertThat(f1.version()).isEqualTo(SelectionModel.VERSION);
    assertThat(f1.minSectorSize()).isEqualTo(8);
    assertThat(f1.minMetricN()).isEqualTo(5);
    assertThat(f1.cuts()).containsExactly(0.10, 0.30, 0.70, 0.90);
    assertThat(f1.ratingScope()).isEqualTo(ModelSpec.RatingScope.UNIVERSE);
    assertThat(f1.priceMin()).isEqualTo(10.0);
    assertThat(f1.minQuarters()).isEqualTo(8);
    assertThat(f1.staleDays()).isEqualTo(7);
    assertThat(f1.metrics()).hasSize(11).doesNotContain(Metric.MOMENTUM_12_1);
    assertThat(f1.factors()).containsExactlyElementsOf(EnumSet.allOf(Factor.class));
  }

  @Test
  void 预登记清单_候选与诊断分开() {
    assertThat(ModelSpecs.all())
        .extracting(ModelSpec::version)
        .containsExactly(
            "F1",
            "F1A",
            "F1B",
            "F1C",
            "F1D",
            "F1E",
            "DIAG_VALUE",
            "DIAG_GROWTH",
            "DIAG_PROFIT",
            "DIAG_MOMENTUM");
    assertThat(ModelSpecs.find("F1A").orElseThrow().metrics())
        .contains(Metric.MOMENTUM_12_1)
        .doesNotContain(Metric.MOMENTUM_3M, Metric.MOMENTUM_6M, Metric.MOMENTUM_12M);
    assertThat(ModelSpecs.find("DIAG_VALUE").orElseThrow().candidate()).isFalse();
    assertThat(ModelSpecs.find("F1E").orElseThrow().candidate()).isTrue();
    assertThat(ModelSpecs.find("nope")).isEmpty();
  }

  /**
   * Momentum ordered against everything else: in F1 the best name scores (3 × 100 + 0) ÷ 4 = 75, in
   * F1C (no momentum) it scores 100.
   */
  @Test
  void 去掉动能后合成分只看其余三因子() {
    List<StockInputs> in = new ArrayList<>();
    for (int i = 1; i <= 10; i++) {
      in.add(stock(i, "IT", i, -i));
    }
    List<StockScore> f1 = SelectionModel.score(in);
    List<StockScore> f1c = SelectionModel.score(in, ModelSpecs.find("F1C").orElseThrow());

    assertThat(
            f1.stream().filter(s -> s.instrumentId() == 10).findFirst().orElseThrow().composite())
        .isEqualTo(75.0);
    StockScore best = f1c.stream().filter(s -> s.instrumentId() == 10).findFirst().orElseThrow();
    assertThat(best.composite()).isEqualTo(100.0);
    assertThat(best.rating()).isEqualTo(Rating.STRONG_BUY);
    assertThat(best.factorScores()).doesNotContainKey(Factor.MOMENTUM);
  }

  /** 8 + 12 names: across all 20 the top 10% is 2 names; within sectors it is 1 + 2. */
  @Test
  void 板块内强制分布() {
    List<StockInputs> in = new ArrayList<>();
    for (int i = 1; i <= 8; i++) {
      in.add(stock(i, "A", i, i));
    }
    for (int i = 1; i <= 12; i++) {
      in.add(stock(100 + i, "B", i, i));
    }
    List<StockScore> all = SelectionModel.score(in);
    List<StockScore> bySector = SelectionModel.score(in, ModelSpecs.find("F1E").orElseThrow());

    assertThat(all.stream().filter(s -> s.rating() == Rating.STRONG_BUY)).hasSize(2);
    assertThat(bySector.stream().filter(s -> s.rating() == Rating.STRONG_BUY)).hasSize(3);
    assertThat(rating(bySector, 8)).isEqualTo(Rating.STRONG_BUY);
    assertThat(rating(bySector, 112)).isEqualTo(Rating.STRONG_BUY);
    assertThat(rating(bySector, 111)).isEqualTo(Rating.STRONG_BUY);
  }
}
