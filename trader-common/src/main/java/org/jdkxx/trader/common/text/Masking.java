package org.jdkxx.trader.common.text;

/**
 * Masking for logs and pages. Values such as account numbers and host names may only appear in
 * output in masked form (the repository is public, and logs and screenshots get shared).
 */
public final class Masking {

  private Masking() {}

  /**
   * Keeps the first {@code keep} characters and replaces the rest with a fixed five asterisks, so
   * the original length is not leaked. Null or blank input returns an empty string; a value no
   * longer than {@code keep} is masked entirely.
   */
  public static String mask(String value, int keep) {
    if (value == null || value.isBlank()) {
      return "";
    }
    String v = value.trim();
    if (keep <= 0 || v.length() <= keep) {
      return "*****";
    }
    return v.substring(0, keep) + "*****";
  }
}
