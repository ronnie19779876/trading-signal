package org.jdkxx.trader.core.marketdata.bars;

/**
 * Semantics of price-adjustment factors. CUMULATIVE = each factor from the broker already
 * accumulates every event "from that ex-date to today", so taking the one closest to the target
 * date is enough. PER_EVENT = each factor is only that event's own ratio, and the events after the
 * target date have to be composed in time order. Measured (FutuMarketDataIT, AAPL 2026-04-20 to
 * 05-20, spanning two ex-dividend dates): PER_EVENT matches Futu's forward-adjusted series with a
 * maximum relative error of 1.8e-5, CUMULATIVE 8.5e-4. Futu's factors are per-event ratios and must
 * be composed event by event. Default PER_EVENT.
 */
public enum FactorMode {
  CUMULATIVE,
  PER_EVENT
}
