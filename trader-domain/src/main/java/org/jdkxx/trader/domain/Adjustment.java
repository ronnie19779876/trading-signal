package org.jdkxx.trader.domain;

/**
 * Price adjustment basis for bars. Storage is always unadjusted; adjustment is computed on read.
 */
public enum Adjustment {
  NONE,
  FORWARD,
  BACKWARD
}
