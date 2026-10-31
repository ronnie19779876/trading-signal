package org.jdkxx.trader.common.time;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * Market clock. The system targets US equities, so every "trading day" notion is computed in
 * America/New_York; storage is always UTC (timestamptz) and conversion happens only for display.
 * The injected {@link Clock} lets tests pin the time; its own zone is ignored because every derived
 * value is converted to New York explicitly.
 */
public final class MarketClock {

  public static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

  private final Clock clock;

  public MarketClock(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public static MarketClock system() {
    return new MarketClock(Clock.system(NEW_YORK));
  }

  public Instant now() {
    return clock.instant();
  }

  public ZonedDateTime nowNewYork() {
    return now().atZone(NEW_YORK);
  }

  /**
   * Current US/Eastern date. Note that this is only the calendar date; whether it is a trading day
   * is decided by the trading calendar (implemented in a later phase).
   */
  public LocalDate newYorkDate() {
    return nowNewYork().toLocalDate();
  }
}
