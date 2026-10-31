package org.jdkxx.trader.domain.holding;

import org.jdkxx.trader.domain.signal.SentinelThresholds;

/**
 * Trend and review rules of the holdings risk panel (3.5.0, ARCHITECTURE §29). The long moving
 * average, slope lookback, ATR and chandelier parameters <b>are taken over from the entry signal's
 * sentinel-v1</b> (the trend state is the same judgement as the entry signal's trend gate, not a
 * separate one); the short moving average, the number of days to confirm a break and whether to
 * watch the death cross are this panel's own parameters (approved by the user on 10-30).
 *
 * @param smaShort short moving average (50 days)
 * @param smaLong long moving average (200 days, as in sentinel-v1)
 * @param slopeLookback the long average is "rising" when today's value &gt; the value this many
 *     days ago (as in sentinel-v1's trend gate)
 * @param breakConfirmDays the close must stay at or below the long average for this many
 *     consecutive days for a confirmed break (so that a single dip does not make the state flip
 *     back and forth)
 * @param deathCross whether the short average below the long average is a review condition
 * @param atrPeriod ATR period (Wilder, as in sentinel-v1)
 * @param chandelierPeriod chandelier: highest high over this many latest bars (as in sentinel-v1
 *     and the simulated trading)
 * @param chandelierAtrMultiple chandelier: the highest high minus this many ATRs
 */
public record HoldingRules(
    int smaShort,
    int smaLong,
    int slopeLookback,
    int breakConfirmDays,
    boolean deathCross,
    int atrPeriod,
    int chandelierPeriod,
    double chandelierAtrMultiple) {

  public HoldingRules {
    if (smaShort < 2
        || smaLong <= smaShort
        || slopeLookback < 1
        || breakConfirmDays < 1
        || atrPeriod < 2
        || chandelierPeriod < 1
        || chandelierAtrMultiple <= 0) {
      throw new IllegalArgumentException("持仓复核参数不合法");
    }
  }

  public static HoldingRules of(
      SentinelThresholds th, int smaShort, int breakConfirmDays, boolean deathCross) {
    return new HoldingRules(
        smaShort,
        th.smaLong(),
        th.smaSlopeLookback(),
        breakConfirmDays,
        deathCross,
        th.atrPeriod(),
        th.chandelierPeriod(),
        th.chandelierAtrMultiple());
  }

  /** Minimum number of bars needed for a verdict: long average + slope lookback. */
  public int minBars() {
    return smaLong + slopeLookback;
  }
}
