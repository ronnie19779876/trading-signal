package org.jdkxx.trader.domain;

/**
 * Tracked indices. {@code classification} is the industry classification scheme used by the index's
 * constituent table. {@code nominalSize} is the number of companies the index itself defines (not
 * the number of rows in our database): the actual number of symbols is slightly higher because each
 * share class of a company takes its own row (e.g. GOOGL / GOOG and BRK.B in the S&amp;P 500). The
 * daily check uses it to detect a shrunken constituent set.
 */
public enum IndexCode {
  SP500("标普 500", "GICS", 500),
  NDX100("纳斯达克 100", "ICB", 100);

  private final String displayName;
  private final String classification;
  private final int nominalSize;

  IndexCode(String displayName, String classification, int nominalSize) {
    this.displayName = displayName;
    this.classification = classification;
    this.nominalSize = nominalSize;
  }

  /**
   * Number of companies defined by the index; the actual symbol count may be slightly higher
   * (multiple share classes).
   */
  public int nominalSize() {
    return nominalSize;
  }

  public String displayName() {
    return displayName;
  }

  public String classification() {
    return classification;
  }
}
