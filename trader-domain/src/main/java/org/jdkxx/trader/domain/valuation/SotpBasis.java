package org.jdkxx.trader.domain.valuation;

/**
 * 反推与核对要用的公司级底座，全部由系统自动带入，不用手填。
 *
 * @param currentPrice     现价（最近收盘）
 * @param baselinePe       本益比基准（日 K 市盈率有效值的中位数；<b>0 视作无效已被剔除</b>）；取不到为 null，反推不给结论
 * @param currentNetIncome 当前归母净利（TTM 或最近一期年化），用于反推所需增长；取不到或非正为 null
 * @param actualNetMargin  最近一期实际合并净利率，<b>小数</b>；用于核对分业务净利率是否自相矛盾，取不到为 null
 */
public record SotpBasis(double currentPrice, Double baselinePe, Double currentNetIncome, Double actualNetMargin) {
}
