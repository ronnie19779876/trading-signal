package org.jdkxx.trader.domain.signal;

/**
 * 出场预案（不是判定门）：初始止损、+1R 参考价、吊灯止损、时间止损、上方压力区参考目标。
 * 按分享稿的配对检验结论，<b>不含 +1R 减半仓</b>（两份独立样本上减半仓都显著更差：它截断了厚尾收益）。
 *
 * @param initialStop     初始止损（第四门算出的）
 * @param riskPerShare    单位风险 R = 判定日收盘 − 止损
 * @param plusOneR        判定日收盘 + R，时间止损的参照
 * @param chandelierStop  22 日最高 − 3×ATR14（判定日口径，之后逐日重算、只上移）
 * @param timeStopDays    该交易日数内未到 +1R 即离场
 * @param targetZone      上方最近的压力区（分形高点聚类），没有则为 null
 * @param target          上方最近压力区的区底，没有则为 null
 * @param rewardRisk      (target − 收盘) ÷ R，仅作参考，不做事前盈亏比判定
 */
public record ExitPlan(double initialStop, double riskPerShare, double plusOneR, double chandelierStop,
                       int timeStopDays, PriceZone targetZone, Double target, Double rewardRisk) {
}
