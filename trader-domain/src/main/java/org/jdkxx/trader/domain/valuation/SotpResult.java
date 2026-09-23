package org.jdkxx.trader.domain.valuation;

import java.util.List;
import java.util.Map;

/**
 * 分部估值的计算结果。只做算术与一致性核对，不给建议、不预测。
 *
 * @param horizonYears   折现期（年）
 * @param discountFactor 折现系数
 * @param scenarios      三个情景各一份结果
 * @param sensitivities  单因子敏感度：只动一条业务线、其余保持基准
 * @param marginCheck    净利率口径核对
 * @param reverse        反推；缺本益比基准时为 null
 */
public record SotpResult(double horizonYears, double discountFactor,
                         Map<SotpScenario, ScenarioValue> scenarios,
                         List<Sensitivity> sensitivities,
                         MarginCheck marginCheck, Reverse reverse) {

    /**
     * 一个情景的结果。
     *
     * @param segmentValues 每条业务线的价值，按输入顺序
     * @param segmentTotal  业务价值合计
     * @param equityValue   股权价值 = 业务价值合计 + 目标年净现金
     * @param targetPrice   目标年股价
     * @param presentValue  折回基准日的股价
     * @param upsidePct     折现后相对现价的涨跌幅，<b>百分数</b>；现价非正时为 null
     */
    public record ScenarioValue(Map<String, Double> segmentValues, double segmentTotal, double equityValue,
                                double targetPrice, double presentValue, Double upsidePct) {
    }

    /**
     * 单因子敏感度：把这条业务线在熊 / 牛之间摆动、其余业务线保持基准，折现后的股价区间。
     * TSLA 实测里五条业务线只有 Robotaxi 一条的摆幅是量级级别的，其余各约 ±$40。
     */
    public record Sensitivity(String segment, double lowPresentValue, double highPresentValue, double swing) {
    }

    /**
     * 净利率口径核对：分业务净利加总 ÷ 分业务营收加总，和最近一期实际合并净利率比。
     * 这条最能挡住「每块都很赚、加起来对不上公司」的假设。
     *
     * @param impliedNetMargin 基准情景的隐含合并净利率，<b>小数</b>
     * @param actualNetMargin  最近一期实际，<b>小数</b>；取不到为 null
     * @param deviationPp      隐含 − 实际，<b>百分点</b>；实际取不到为 null
     * @param ok               取不到实际值时为 true（无从判断，不拦），否则看偏离是否在阈值内
     */
    public record MarginCheck(double impliedNetMargin, Double actualNetMargin, Double deviationPp, boolean ok) {
    }

    /**
     * 反推：不问「值多少」，问「现价已经押注了什么」。
     *
     * @param requiredTargetPrice     按要求回报率，目标年需要达到的股价
     * @param requiredMarketCap       对应的目标年市值
     * @param requiredNetIncome       按本益比基准倒推的目标年所需净利
     * @param requiredNetIncomeCagr   从当前净利到所需净利的年化增长，<b>小数</b>；当前净利取不到或非正时为 null
     */
    public record Reverse(double requiredTargetPrice, double requiredMarketCap, double requiredNetIncome,
                          Double requiredNetIncomeCagr) {
    }
}
