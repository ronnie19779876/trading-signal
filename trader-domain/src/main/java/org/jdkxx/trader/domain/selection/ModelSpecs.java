package org.jdkxx.trader.domain.selection;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Pre-registered model specs for the historical test (ARCHITECTURE §30.7). Each candidate changes
 * exactly one thing against F1. The list is fixed before results are looked at; adding a variant is
 * a commit, and the report counts how many candidates were ever tried on the training segment.
 */
public final class ModelSpecs {

  private static final Set<Metric> MOMENTUM_ALL =
      EnumSet.of(Metric.MOMENTUM_3M, Metric.MOMENTUM_6M, Metric.MOMENTUM_12M);

  private static final Map<String, ModelSpec> ALL = new LinkedHashMap<>();

  static {
    ModelSpec f1 = ModelSpec.F1;
    register(f1);
    register(
        f1.with(
            "F1A",
            "动能只用 12−1（第 t−252 到 t−21 个交易日的涨幅）",
            replaceMomentum(EnumSet.of(Metric.MOMENTUM_12_1)),
            f1.factors()));
    register(
        f1.with(
            "F1B",
            "动能只用 6、12 个月",
            replaceMomentum(EnumSet.of(Metric.MOMENTUM_6M, Metric.MOMENTUM_12M)),
            f1.factors()));
    register(
        f1.with(
            "F1C",
            "去掉动能（估值、成长、盈利能力三因子）",
            f1.metrics(),
            EnumSet.of(Factor.VALUE, Factor.GROWTH, Factor.PROFITABILITY)));
    register(
        f1.with(
            "F1D", "只用估值 + 盈利能力", f1.metrics(), EnumSet.of(Factor.VALUE, Factor.PROFITABILITY)));
    register(f1.withRatingScope("F1E", "评级在板块内做强制分布", ModelSpec.RatingScope.SECTOR));
    register(single("DIAG_VALUE", Factor.VALUE, "诊断：只用估值"));
    register(single("DIAG_GROWTH", Factor.GROWTH, "诊断：只用成长"));
    register(single("DIAG_PROFIT", Factor.PROFITABILITY, "诊断：只用盈利能力"));
    register(single("DIAG_MOMENTUM", Factor.MOMENTUM, "诊断：只用动能"));
  }

  private ModelSpecs() {}

  public static List<ModelSpec> all() {
    return List.copyOf(ALL.values());
  }

  public static Optional<ModelSpec> find(String version) {
    return Optional.ofNullable(ALL.get(version));
  }

  private static void register(ModelSpec spec) {
    if (ALL.putIfAbsent(spec.version(), spec) != null) {
      throw new IllegalStateException("规格名重复：" + spec.version());
    }
  }

  private static Set<Metric> replaceMomentum(Set<Metric> momentum) {
    Set<Metric> m = EnumSet.copyOf(ModelSpec.F1.metrics());
    m.removeAll(MOMENTUM_ALL);
    m.addAll(momentum);
    return m;
  }

  private static ModelSpec single(String version, Factor factor, String description) {
    Set<Metric> m = EnumSet.noneOf(Metric.class);
    for (Metric x : ModelSpec.F1.metrics()) {
      if (x.factor() == factor) {
        m.add(x);
      }
    }
    return ModelSpec.F1.with(version, description, m, EnumSet.of(factor)).diagnostic();
  }
}
