package org.jdkxx.trader.domain.selection;

/**
 * Time of day of an earnings announcement. Scoring runs after the close and execution happens the
 * next day, so the point-in-time rules do not distinguish it (§25.5); it is kept for the record.
 * (The entry-signal AI hints and the holdings panel do use it, see {@code EarningsReaction}.)
 */
public enum EarningsTiming {
  PRE_MARKET,
  DURING_MARKET,
  AFTER_MARKET,
  UNKNOWN
}
