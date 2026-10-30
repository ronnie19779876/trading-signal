package org.jdkxx.trader.domain.holding;

import org.jdkxx.trader.domain.signal.SentinelThresholds;

/**
 * 持仓风险面板（3.5.0，ARCHITECTURE §29）的趋势与复核口径。长均线、斜率回看、ATR、吊灯参数<b>沿用入场信号的 sentinel-v1</b>
 * （趋势状态与入场信号的趋势门是同一个判断，不另造一套）；短均线、破位确认天数、是否看死叉是本面板自己的参数（用户 10-30 认可）。
 *
 * @param smaShort              短均线（50 日）
 * @param smaLong               长均线（200 日，同 sentinel-v1）
 * @param slopeLookback         长均线「在上升」= 今天的值 > 这么多天前的值（同 sentinel-v1 的趋势门）
 * @param breakConfirmDays      连续这么多天收盘低于长均线才算破位确认（避免偶尔跌破一天就来回跳）
 * @param deathCross            短均线在长均线之下是否作为复核条件
 * @param atrPeriod             ATR 周期（Wilder，同 sentinel-v1）
 * @param chandelierPeriod      吊灯：最近这么多根 K 线的最高价（同 sentinel-v1 与模拟交易）
 * @param chandelierAtrMultiple 吊灯：最高价减去这么多倍 ATR
 */
public record HoldingRules(int smaShort, int smaLong, int slopeLookback, int breakConfirmDays, boolean deathCross,
                           int atrPeriod, int chandelierPeriod, double chandelierAtrMultiple) {

    public HoldingRules {
        if (smaShort < 2 || smaLong <= smaShort || slopeLookback < 1 || breakConfirmDays < 1 || atrPeriod < 2
                || chandelierPeriod < 1 || chandelierAtrMultiple <= 0) {
            throw new IllegalArgumentException("持仓复核参数不合法");
        }
    }

    public static HoldingRules of(SentinelThresholds th, int smaShort, int breakConfirmDays, boolean deathCross) {
        return new HoldingRules(smaShort, th.smaLong(), th.smaSlopeLookback(), breakConfirmDays, deathCross,
                th.atrPeriod(), th.chandelierPeriod(), th.chandelierAtrMultiple());
    }

    /** 判定所需的最少 K 线数：长均线 + 斜率回看。 */
    public int minBars() {
        return smaLong + slopeLookback;
    }
}
