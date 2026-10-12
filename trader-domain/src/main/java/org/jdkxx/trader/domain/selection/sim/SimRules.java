package org.jdkxx.trader.domain.selection.sim;

import org.jdkxx.trader.domain.signal.SentinelThresholds;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 量化选股模拟交易的规则（ARCHITECTURE §26）。和评分模型一样<b>跟着版本写死在代码里</b>：改任何一条就换版本，
 * 不同版本的成交不可比。2026-10-12 用户拍板：等权、止损距离封顶 10%、没有上方压力区用 +2R、成本按 0。
 *
 * @param initialCapital   模拟资金（美元）
 * @param slots            同时最多持有（含待成交）几笔；每笔名义 = (初始资金 + 已实现盈亏) ÷ slots，受可用现金限制
 * @param topN             只有最新评分日合成分前几名可以加入
 * @param maxStopDistance  止损距离（相对评分日收盘）上限，超过就把止损上移到这个距离（记 CAP）
 * @param targetFallbackR  没有上方压力区（或开盘已越过它）时，止盈目标 = 入场 + 这么多倍 R
 * @param sentinel         止损公式、吊灯止损、压力区的参数沿用入场信号的判据版本
 */
public record SimRules(String version, double initialCapital, int slots, int topN, double maxStopDistance,
                       double targetFallbackR, SentinelThresholds sentinel) {

    public static final SimRules V1 = new SimRules("sim-v1", 500_000, 10, 10, 0.10, 2.0, SentinelThresholds.V1);

    /** 落库与接口返回用，键序固定。 */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", version);
        m.put("initialCapital", initialCapital);
        m.put("slots", slots);
        m.put("topN", topN);
        m.put("sizing", "等权：每笔名义 = (初始资金 + 已实现盈亏) ÷ " + slots + "，受可用现金限制，整股");
        m.put("entry", "点击之后的第一个开盘价；开盘已在止损之下则取消");
        m.put("stop", "min(评分日收盘 − " + sentinel.stopAtrMultiple() + "×ATR14, 支撑区底 − " + sentinel.zoneStopAtrMultiple()
                + "×ATR14)，距离超过 " + Math.round(maxStopDistance * 100) + "% 封顶");
        m.put("trailing", "盘中触及 +1R（入场 + R）后启用吊灯止损：" + sentinel.chandelierPeriod() + " 日最高 − "
                + sentinel.chandelierAtrMultiple() + "×ATR14，只上移");
        m.put("stopExit", "收盘 ≤ 有效止损时按当天收盘自动平仓");
        m.put("target", "上方最近压力区的区底；没有或开盘已越过时 = 入场 + " + targetFallbackR + "R。到价只提示，由人决定是否平仓");
        m.put("manualExit", "点击之后的第一个开盘价");
        m.put("timeStop", "不用");
        m.put("costs", "不计佣金、滑点；价格只按结构性事件调整，不含普通分红（SPY 对照同口径）");
        m.put("sentinelVersion", sentinel.version());
        return m;
    }
}
