package org.jdkxx.trader.gateway.futu.mapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.jdkxx.trader.domain.Instrument;

/**
 * double → BigDecimal for Futu replies. {@code BigDecimal.valueOf(double)} throws a bare
 * NumberFormatException on NaN / ±∞ that names neither the symbol nor the field, and one such value
 * used to fail a whole reply (found in the 2026-10-31 review). Nullable fields therefore become
 * null; fields a record cannot do without (OHLC, adjustment factors, curPrice) fail loudly with the
 * symbol, date and field name instead of being silently skipped (the user's decision).
 */
final class FutuNumbers {

  private FutuNumbers() {}

  /** Nullable field: a non-finite value means "no data". */
  static BigDecimal orNull(double v) {
    return Double.isFinite(v) ? BigDecimal.valueOf(v) : null;
  }

  /** Nullable field, rounded to {@code scale} with trailing zeros stripped. */
  static BigDecimal orNull(double v, int scale) {
    BigDecimal d = orNull(v);
    return d == null ? null : d.setScale(scale, RoundingMode.HALF_UP).stripTrailingZeros();
  }

  /** Required field: a non-finite value is a broker data error for that record. */
  static BigDecimal required(
      double v, int scale, String field, Instrument instrument, Object when) {
    if (!Double.isFinite(v)) {
      throw new IllegalStateException(
          "富途返回的 %s 不是有限数（%s）：%s %s".formatted(field, v, instrument, when));
    }
    return BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP).stripTrailingZeros();
  }
}
