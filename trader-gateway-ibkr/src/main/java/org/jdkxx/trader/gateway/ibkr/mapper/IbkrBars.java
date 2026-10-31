package org.jdkxx.trader.gateway.ibkr.mapper;

import com.ib.client.Bar;
import com.ib.client.Decimal;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.jdkxx.trader.domain.selection.SplitAdjustedBar;

/**
 * IBKR historical daily bars to domain. TRADES bars are already split-adjusted (read-only probe on
 * 2026-10-10, measured on NVDA and AAPL), so no price adjustment is applied here. With formatDate=1
 * a daily bar's time string is {@code yyyyMMdd}; entries that cannot be parsed or have a
 * non-positive price are dropped, never invented.
 */
public final class IbkrBars {

  private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;
  private static final DateTimeFormatter HEAD = DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss");
  private static final ZoneId EXCHANGE = ZoneId.of("America/New_York");

  private IbkrBars() {}

  public static List<SplitAdjustedBar> toSplitAdjusted(
      List<Bar> bars, LocalDate from, LocalDate to) {
    List<SplitAdjustedBar> out = new ArrayList<>(bars.size());
    for (Bar b : bars) {
      LocalDate d = day(b.time());
      // Spans over a year are requested in whole years (rounded up), so bars outside [from, to] are
      // expected and trimmed here
      if (d == null || d.isBefore(from) || d.isAfter(to) || !(b.close() > 0)) {
        continue;
      }
      out.add(
          new SplitAdjustedBar(
              d, dec(b.open()), dec(b.high()), dec(b.low()), dec(b.close()), volume(b.volume())));
    }
    return out;
  }

  static LocalDate day(String time) {
    if (time == null || time.length() < 8) {
      return null;
    }
    try {
      return LocalDate.parse(time.substring(0, 8), DAY);
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * Earliest data time (reqHeadTimestamp, formatDate=1) to a US/Eastern trading day. The time
   * string is UTC {@code yyyyMMdd-HH:mm:ss}; two queries for the same instrument can return
   * different times of day (measured 2026-10-17: SKYD first answered 08:00, later 13:30, both on
   * 2025-08-07), so only the date is used. Returns null when it cannot be parsed, never invents
   * one.
   */
  public static LocalDate headDay(String headTimestamp) {
    if (headTimestamp == null) {
      return null;
    }
    try {
      return LocalDateTime.parse(headTimestamp.strip(), HEAD)
          .atOffset(ZoneOffset.UTC)
          .atZoneSameInstant(EXCHANGE)
          .toLocalDate();
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static BigDecimal dec(double v) {
    return v > 0 && Double.isFinite(v) ? BigDecimal.valueOf(v) : null;
  }

  private static long volume(Decimal v) {
    return v == null || !v.isValid() ? 0 : v.value().longValue();
  }
}
