package org.jdkxx.trader.domain.selection;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Golden test of the {@link ModelSpec} refactor: on random inputs, scoring with {@link
 * ModelSpec#F1} must equal the pre-refactor implementation ({@link LegacyF1Model}) exactly, every
 * field and every bit. Inputs cover missing values, ties, small sectors and metric groups, the
 * financial templates, excluded names and the variant-only metric that F1 must ignore.
 */
class SelectionModelF1ReferenceTest {

  private static final String[] SECTORS = {"IT", "FIN", "HC", "EN", "RE"};

  @Test
  void 随机输入下与重构前逐字段相同() {
    Random rnd = new Random(20261031L);
    int compared = 0;
    for (int trial = 0; trial < 300; trial++) {
      List<StockInputs> in = inputs(rnd);
      List<StockScore> legacy = LegacyF1Model.score(in);
      List<StockScore> now = SelectionModel.score(in, ModelSpec.F1);
      assertThat(now).as("trial %d", trial).isEqualTo(legacy);
      compared += now.size();
    }
    assertThat(compared).isGreaterThan(10_000);
  }

  private static List<StockInputs> inputs(Random rnd) {
    List<StockInputs> out = new ArrayList<>();
    long id = 1;
    for (String sector : SECTORS) {
      int n = 3 + rnd.nextInt(28);
      for (int i = 0; i < n; i++) {
        boolean financial = rnd.nextDouble() < 0.15;
        Set<Metric> applicable = EnumSet.allOf(Metric.class);
        if (financial) {
          applicable.removeAll(EnumSet.of(Metric.GROSS_MARGIN, Metric.FCF_MARGIN));
        }
        Map<Metric, Double> m = new EnumMap<>(Metric.class);
        for (Metric x : Metric.values()) {
          if (rnd.nextDouble() < 0.2 || (financial && !applicable.contains(x))) {
            continue;
          }
          // One decimal makes ties frequent
          m.put(x, Math.round((rnd.nextGaussian() * 3) * 10) / 10.0);
        }
        Exclusion ex = rnd.nextDouble() < 0.1 ? Exclusion.PRICE_BELOW_MIN : null;
        out.add(new StockInputs(id, "S" + id, sector, ex, ex == null ? null : "x", m, applicable));
        id++;
      }
    }
    return out;
  }
}
