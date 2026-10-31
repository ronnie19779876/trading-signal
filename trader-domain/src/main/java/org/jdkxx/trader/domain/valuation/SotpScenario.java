package org.jdkxx.trader.domain.valuation;

/**
 * Scenarios of a sum-of-the-parts valuation. All three must be given together: a single-point
 * valuation is meaningless for option-like businesses (Robotaxi, Semi and the like); in the TSLA
 * test of 2026-09-19, moving Robotaxi alone pulled the 2030 share price from $71 to $385.
 */
public enum SotpScenario {
  BEAR,
  BASE,
  BULL
}
