package org.jdkxx.trader.core.account;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;

/**
 * 持仓风险面板（{@code trader.holding-review.*}，3.5.0，ARCHITECTURE §29）。默认值为用户 2026-10-30 认可的方案取值。
 *
 * @param windowDays       财报预警窗口：未来这么多个交易日内的财报要提示
 * @param stressMultiple   压力情景 = 历史最大跌幅 × 这个倍数（GOOG 2026-10-29 跌 12.3%，是此前 19 次最大值的 1.28 倍）
 * @param lossBudgetPct    单只标的过财报最多愿意亏净值的百分之几；<b>由用户自己定，不配就不判超预算、不给仓位换算</b>
 * @param smaShort         短均线
 * @param breakConfirmDays 连续这么多天收盘低于长均线才算破位确认
 * @param deathCross       短均线在长均线之下是否作为复核条件
 * @param includePool      池里没持有的标的也给（没有金额，只有历史分布与仓位换算），用于加仓前参考
 */
@ConfigurationProperties(prefix = "trader.holding-review")
public record HoldingReviewProperties(
        @DefaultValue("10") int windowDays,
        @DefaultValue("1.5") double stressMultiple,
        BigDecimal lossBudgetPct,
        @DefaultValue("50") int smaShort,
        @DefaultValue("3") int breakConfirmDays,
        @DefaultValue("true") boolean deathCross,
        @DefaultValue("true") boolean includePool) {
}
