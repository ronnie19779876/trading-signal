package org.jdkxx.trader.domain.selection.sim;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jdkxx.trader.domain.signal.SentinelThresholds;

/**
 * Rules of the quant-selection simulated trading (ARCHITECTURE §26). Like the scoring model they
 * are <b>hard-coded per version</b>: changing any rule means a new version, and fills of different
 * versions are not comparable. Decided by the user on 2026-10-12: equal weight, stop distance
 * capped at 10%, +2R when there is no resistance zone above, costs taken as 0.
 *
 * @param initialCapital simulated capital (USD)
 * @param slots maximum number of trades held at once (including pending ones); notional per trade =
 *     (initial capital + realized P&amp;L) ÷ slots, limited by available cash
 * @param topN only the top this-many instruments by composite score on the latest scoring day may
 *     be added
 * @param maxStopDistance maximum stop distance (relative to the scoring-day close); beyond it the
 *     stop is raised to this distance (recorded as CAP)
 * @param targetFallbackR without a resistance zone above (or when the open is already past it), the
 *     take-profit target = entry + this many R
 * @param sentinel the stop formula, chandelier stop and resistance-zone parameters come from this
 *     entry-signal criteria version
 */
public record SimRules(
    String version,
    double initialCapital,
    int slots,
    int topN,
    double maxStopDistance,
    double targetFallbackR,
    SentinelThresholds sentinel) {

  public static final SimRules V1 =
      new SimRules("sim-v1", 500_000, 10, 10, 0.10, 2.0, SentinelThresholds.V1);

  /** For persistence and API responses; fixed key order. */
  public Map<String, Object> toMap() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("version", version);
    m.put("initialCapital", initialCapital);
    m.put("slots", slots);
    m.put("topN", topN);
    m.put("sizing", "等权：每笔名义 = (初始资金 + 已实现盈亏) ÷ " + slots + "，受可用现金限制，整股");
    m.put("entry", "点击之后的第一个开盘价；开盘已在止损之下则取消");
    m.put(
        "stop",
        "min(评分日收盘 − "
            + sentinel.stopAtrMultiple()
            + "×ATR14, 支撑区底 − "
            + sentinel.zoneStopAtrMultiple()
            + "×ATR14)，距离超过 "
            + Math.round(maxStopDistance * 100)
            + "% 封顶");
    m.put(
        "trailing",
        "盘中触及 +1R（入场 + R）后启用吊灯止损："
            + sentinel.chandelierPeriod()
            + " 日最高 − "
            + sentinel.chandelierAtrMultiple()
            + "×ATR14，只上移");
    m.put("stopExit", "收盘 ≤ 有效止损时按当天收盘自动平仓");
    m.put("target", "上方最近压力区的区底；没有或开盘已越过时 = 入场 + " + targetFallbackR + "R。到价只提示，由人决定是否平仓");
    m.put("manualExit", "点击之后的第一个开盘价");
    m.put("timeStop", "不用");
    m.put("costs", "不计佣金、滑点；价格只按结构性事件调整，不含普通分红（SPY 对照同口径）");
    m.put("sentinelVersion", sentinel.version());
    return m;
  }
}
