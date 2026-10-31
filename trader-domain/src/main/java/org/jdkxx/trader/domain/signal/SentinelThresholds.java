package org.jdkxx.trader.domain.signal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Criteria parameters of the entry sentinel. <b>Parameters belong to a criteria version and are
 * hard-coded</b>; external configuration can only select a version, never change a threshold. Any
 * change to a formula or to the meaning of a threshold requires a new version, and conclusions of
 * different versions are not comparable.
 *
 * <p>The parameters fall into these groups (the criterion text and the persisted record carry the
 * full set):
 *
 * <ul>
 *   <li><b>standard parameters</b>, changing them means it is no longer that indicator: SMA200,
 *       fractals with 2 bars on each side (Bill Williams), ATR 14 / Wilder (1978), MACD 12/26/9
 *       (Appel), chandelier stop 22 days × 3.0 (LeBeau);
 *   <li><b>conventional parameters</b> without an industry standard: 20-day lookback for the
 *       moving-average slope, clustering tolerance 0.5×ATR14, 252-day fractal lookback, RVOL 1.5 /
 *       20 days, the two stop legs 2.0×ATR and zone bottom − 0.5×ATR, maximum stop distance 10%,
 *       time stop 20 / validity 2 / cooldown 5 trading days, a data window of 600 calendar days
 *       with at least 260 bars;
 *   <li><b>data quality</b> (added by this system): at most 3 trading days missing in the window
 *       against the trading calendar (suspension blank bars count as missing); with more missing,
 *       nothing is evaluated, because indicators over a shifted series give well-formed but wrong
 *       conclusions;
 *   <li><b>user specification</b>: a valid zone needs at least 2 touches.
 * </ul>
 *
 * The values are taken over from futu-trader's entry-signal sentinel entry-v3 (see that project's
 * write-up for the validation: tuning the thresholds was not significant out of sample, so they are
 * not tuned).
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

  public static final SentinelThresholds V1 =
      new SentinelThresholds(
          "sentinel-v1",
          260,
          3,
          600,
          200,
          20,
          2,
          14,
          12,
          26,
          9,
          252,
          0.5,
          2,
          1.5,
          20,
          2.0,
          0.5,
          0.10,
          22,
          3.0,
          20,
          2,
          5,
          List.of(0.382, 0.5, 0.618));

  public SentinelThresholds {
    fibonacciLevels = List.copyOf(fibonacciLevels);
  }

  /** For persistence and API responses; fixed key order. */
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
