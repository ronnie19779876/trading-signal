package org.jdkxx.trader.domain.selection;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 一只标的某天的打分输入。exclusion 非空表示不参与（指标照样带上，便于审计）。
 *
 * @param applicable 该标的适用的指标（银行、保险模板没有毛利率与自由现金流利润率）；因子分「至少一半指标有值」按它算
 */
public record StockInputs(long instrumentId, String symbol, String sector, Exclusion exclusion, String exclusionDetail,
                          Map<Metric, Double> metrics, Set<Metric> applicable) {

    public StockInputs {
        Objects.requireNonNull(metrics, "metrics");
        Objects.requireNonNull(applicable, "applicable");
    }
}
