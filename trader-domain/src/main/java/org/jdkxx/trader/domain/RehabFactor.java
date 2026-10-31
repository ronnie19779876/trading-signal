package org.jdkxx.trader.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Price-adjustment factor of one ex-rights / ex-dividend event (Futu basis): forward-adjusted price
 * = unadjusted price × fwdA + fwdB (applies to prices before {@code exDate}); backward-adjusted
 * price = unadjusted price × bwdA + bwdB.
 *
 * <p>The factors are <b>per event</b>, not cumulative: adjusting across several events means
 * composing them (forward adjustment from the earliest event to the latest, backward adjustment
 * from the latest to the earliest).
 *
 * <p>{@code companyActFlag} is the corporate-action bitmap (1 split, 2 reverse split /
 * consolidation, 4 bonus shares, 8 capitalization issue, 16 rights issue, 32 additional offering,
 * 64 dividend, 128 special dividend, 256 spin-off). One event can carry several bits at once
 * (measured: 65 = split + dividend, 258 = consolidation + spin-off), in which case fwdA is a
 * blended ratio. When the pure share-count ratio is needed on its own (the structural basis of
 * signal evaluation), use base/ert: a 1-to-10 split is splitBase=1, splitErt=10. fwdA keeps only 5
 * decimal places (WMT's 1:3 split gives 0.33333), so base/ert is also the exact source for pure
 * share-count events.
 */
public record RehabFactor(
    Instrument instrument,
    LocalDate exDate,
    BigDecimal fwdA,
    BigDecimal fwdB,
    BigDecimal bwdA,
    BigDecimal bwdB,
    long companyActFlag,
    BigDecimal dividend,
    BigDecimal spDividend,
    int splitBase,
    int splitErt,
    int joinBase,
    int joinErt,
    int bonusBase,
    int bonusErt,
    int transferBase,
    int transferErt) {

  public static final long ACT_SPLIT = 1;
  public static final long ACT_JOIN = 2;
  public static final long ACT_BONUS = 4;
  public static final long ACT_TRANSFER = 8;
  public static final long ACT_DIVIDEND = 64;
  public static final long ACT_SP_DIVIDEND = 128;
  public static final long ACT_SPIN_OFF = 256;

  /**
   * The older shape with only split ratios (for tests and callers that only care about adjusted
   * prices).
   */
  public RehabFactor(
      Instrument instrument,
      LocalDate exDate,
      BigDecimal fwdA,
      BigDecimal fwdB,
      BigDecimal bwdA,
      BigDecimal bwdB,
      long companyActFlag,
      BigDecimal dividend,
      BigDecimal spDividend,
      int splitBase,
      int splitErt) {
    this(
        instrument,
        exDate,
        fwdA,
        fwdB,
        bwdA,
        bwdB,
        companyActFlag,
        dividend,
        spDividend,
        splitBase,
        splitErt,
        0,
        0,
        0,
        0,
        0,
        0);
  }

  public boolean has(long act) {
    return (companyActFlag & act) != 0;
  }

  public RehabFactor {
    Objects.requireNonNull(instrument, "instrument");
    Objects.requireNonNull(exDate, "exDate");
    fwdA = fwdA == null ? BigDecimal.ONE : fwdA;
    fwdB = fwdB == null ? BigDecimal.ZERO : fwdB;
    bwdA = bwdA == null ? BigDecimal.ONE : bwdA;
    bwdB = bwdB == null ? BigDecimal.ZERO : bwdB;
  }
}
