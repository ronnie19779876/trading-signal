package org.jdkxx.trader.domain.signal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 入场哨兵的判据参数。<b>参数跟着判据版本走，写死在代码里</b>；外置配置只能选版本，不能改阈值——
 * 任何公式或阈值语义的改动都必须升版本，不同版本的判定结论不可比。
 *
 * <p>参数分三类（判定原文与落库都带全集）：
 * <ul>
 *   <li><b>标准参数</b>，改动即不再是该指标：SMA200、分形两侧各 2 根（Bill Williams）、ATR 14 / Wilder（1978）、
 *       MACD 12/26/9（Appel）、吊灯止损 22 日 × 3.0（LeBeau）；</li>
 *   <li><b>惯例参数</b>，无行业标准：均线斜率回看 20 日、聚类容差 0.5×ATR14、分形回看 252 日、RVOL 1.5 / 20 日、
 *       止损两腿 2.0×ATR 与 区底−0.5×ATR、止损距离上限 10%、时间止损 20 / 有效期 2 / 冷却 5 个交易日、
 *       取数窗口 600 自然日与最少 260 根；</li>
 *   <li><b>数据质量</b>（本系统新增）：窗口内对照交易日历最多容忍缺 3 个交易日（停牌空 K 也算缺），
 *       缺得更多就不予判定——整段平移的指标会给出形式正常的错误结论；</li>
 *   <li><b>使用者规格</b>：有效区最少触及 2 次。</li>
 * </ul>
 * 取值沿用 futu-trader 入场信号哨兵 entry-v3（其有效性验证见该项目分享稿：调阈值在样本外不显著，所以不调参）。
 */
public record SentinelThresholds(
        String version,
        int minBars,
        int maxMissingTradingDays,
        int windowCalendarDays,
        int smaLong,
        int smaSlopeLookback,
        int fractalSide,
        int atrPeriod,
        int macdFast,
        int macdSlow,
        int macdSignal,
        int zoneLookback,
        double zoneToleranceAtr,
        int minTouches,
        double rvolThreshold,
        int rvolLookback,
        double stopAtrMultiple,
        double zoneStopAtrMultiple,
        double maxStopDistance,
        int chandelierPeriod,
        double chandelierAtrMultiple,
        int timeStopDays,
        int validityDays,
        int cooldownDays,
        List<Double> fibonacciLevels) {

    public static final SentinelThresholds V1 = new SentinelThresholds(
            "sentinel-v1", 260, 3, 600, 200, 20, 2, 14, 12, 26, 9,
            252, 0.5, 2, 1.5, 20, 2.0, 0.5, 0.10,
            22, 3.0, 20, 2, 5, List.of(0.382, 0.5, 0.618));

    public SentinelThresholds {
        fibonacciLevels = List.copyOf(fibonacciLevels);
    }

    /** 落库与接口返回用，键序固定。 */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", version);
        m.put("minBars", minBars);
        m.put("maxMissingTradingDays", maxMissingTradingDays);
        m.put("windowCalendarDays", windowCalendarDays);
        m.put("smaLong", smaLong);
        m.put("smaSlopeLookback", smaSlopeLookback);
        m.put("fractalSide", fractalSide);
        m.put("atrPeriod", atrPeriod);
        m.put("macd", List.of(macdFast, macdSlow, macdSignal));
        m.put("zoneLookback", zoneLookback);
        m.put("zoneToleranceAtr", zoneToleranceAtr);
        m.put("minTouches", minTouches);
        m.put("rvolThreshold", rvolThreshold);
        m.put("rvolLookback", rvolLookback);
        m.put("stopAtrMultiple", stopAtrMultiple);
        m.put("zoneStopAtrMultiple", zoneStopAtrMultiple);
        m.put("maxStopDistance", maxStopDistance);
        m.put("chandelierPeriod", chandelierPeriod);
        m.put("chandelierAtrMultiple", chandelierAtrMultiple);
        m.put("timeStopDays", timeStopDays);
        m.put("validityDays", validityDays);
        m.put("cooldownDays", cooldownDays);
        m.put("fibonacciLevels", fibonacciLevels);
        return m;
    }
}
