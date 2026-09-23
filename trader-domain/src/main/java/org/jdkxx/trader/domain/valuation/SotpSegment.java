package org.jdkxx.trader.domain.valuation;

import java.util.EnumMap;
import java.util.Map;

/**
 * 一条业务线的假设：量 × 价 = 营收 → × 净利率 = 净利 → × 本益比 = 业务价值。
 *
 * <p><b>净利率是净利率</b>（已扣分摊的研发、管理费用与税），不是毛利率也不是营业利润率——
 * 各业务的净利加总要能对上公司合并净利，{@code SotpCalculator} 会拿实际合并净利率核对。
 *
 * <p>{@code scopeNote} 必填，用来挡住重复计算：同一批车既算进卖车营收、又算进 Robotaxi 与 FSD，
 * 是这个模型最容易犯的错（TSLA 的 FSD 买断收入本就计在卖车营收里）。口径写不出来，就说明还没想清楚。
 *
 * @param name      业务线名称
 * @param scopeNote 口径备注，必填
 * @param cases     三个情景各一组假设，缺一不可
 */
public record SotpSegment(String name, String scopeNote, Map<SotpScenario, SegmentCase> cases) {

    public SotpSegment {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("业务线名称不能为空");
        }
        if (scopeNote == null || scopeNote.isBlank()) {
            throw new IllegalArgumentException("业务线「" + name + "」缺口径备注：写清算什么、不算什么，否则挡不住重复计算");
        }
        if (cases == null) {
            throw new IllegalArgumentException("业务线「" + name + "」缺情景假设");
        }
        Map<SotpScenario, SegmentCase> copy = new EnumMap<>(SotpScenario.class);
        copy.putAll(cases);
        for (SotpScenario s : SotpScenario.values()) {
            if (copy.get(s) == null) {
                throw new IllegalArgumentException("业务线「" + name + "」缺 " + s + " 情景：三个情景必须都给，不接受单点估值");
            }
        }
        cases = Map.copyOf(copy);
    }

    public SegmentCase on(SotpScenario scenario) {
        return cases.get(scenario);
    }

    /**
     * 一个情景下的假设。
     *
     * @param volume    量（单位由使用者自定：辆、份、GWh…，只要与价格口径一致）
     * @param price     单价（与量同口径，得到的是该业务的年营收）
     * @param netMargin 净利率，<b>小数</b>（7% 传 0.07，不是 7）
     * @param pe        本益比。参考区间：汽车 10~20、能源 15~30、软件 20~40；目标年的 PE 取决于目标年之后的增长
     */
    public record SegmentCase(double volume, double price, double netMargin, double pe) {

        public SegmentCase {
            if (volume < 0 || price < 0 || pe < 0) {
                throw new IllegalArgumentException("量、价、本益比不能为负");
            }
            if (netMargin > 1.0 || netMargin < -1.0) {
                throw new IllegalArgumentException("净利率要传小数（7% 传 0.07），收到 " + netMargin);
            }
        }

        public double revenue() {
            return volume * price;
        }

        public double netIncome() {
            return revenue() * netMargin;
        }

        public double value() {
            return netIncome() * pe;
        }
    }
}
