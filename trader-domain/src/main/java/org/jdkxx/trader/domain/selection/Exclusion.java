package org.jdkxx.trader.domain.selection;

/** Why an instrument does not take part in the day's scoring (§25.3); persisted for auditing. */
public enum Exclusion {
  /** IBKR stockType is ADR / NY REG SHRS / REIT, or the type could not be found (no guessing) */
  SECURITY_TYPE,
  /** No GICS sector, and none in the override table either */
  NO_SECTOR,
  /** Too few eligible instruments in the sector group */
  SECTOR_TOO_SMALL,
  /** The day's unadjusted close is below the minimum */
  PRICE_BELOW_MIN,
  /** No usable close as of the day (not yet listed, or suspended for too long) */
  NO_PRICE,
  /** Fewer than 8 quarterly reports announced as of the day */
  INSUFFICIENT_HISTORY
}
