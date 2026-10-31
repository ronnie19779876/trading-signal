package org.jdkxx.trader.domain;

/**
 * Role of an instrument in the pool.
 *
 * <ul>
 *   <li>{@code POOL}: the manually maintained candidate pool (the cap is in configuration; only
 *       this role is capped);
 *   <li>{@code HOLDING}: positions (maintained automatically from IBKR positions since phase 3);
 *   <li>{@code BENCHMARK}: benchmarks (e.g. a Nasdaq-100 ETF): daily bars, price adjustment and
 *       real-time quotes are collected as usual, but they <b>do not take part in stock
 *       selection</b>; when the signal stage scans candidates it must use {@code candidates()}, not
 *       {@code poolAndHoldings()}.
 * </ul>
 */
public enum PoolRole {
  POOL,
  HOLDING,
  BENCHMARK
}
