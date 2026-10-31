package org.jdkxx.trader.core.marketdata.bars;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.jdkxx.trader.core.marketdata.SnapshotWindow;
import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;

/**
 * Bar write cutoff: the most recent trading day whose close has settled at this moment. Bars later
 * than it (today's unfinished bar intraday) are never written.
 *
 * <p>Why it exists: a deep backfill triggered intraday (an instrument newly added by the holdings
 * sync after an intraday IBKR reconnect, a manual pool add) or a manual rotation would store
 * today's unfinished bar; that evening's increment saw "the latest date is already the expected
 * date" and did not re-fetch, so the intraday price stayed in place as the close and the account
 * snapshot valued positions with it (a defect before 2.0.2).
 *
 * <p>Callers evaluate {@link #current()} per batch rather than once per job, so a rotation that
 * crosses 16:15 ET starts writing today's bar as soon as it has settled.
 */
public class SettledCutoff {

  private final TradingDayRepository days;
  private final ZoneId zone;
  private final Clock clock;

  public SettledCutoff(TradingDayRepository days, ZoneId zone, Clock clock) {
    this.days = days;
    this.zone = zone;
    this.clock = clock;
  }

  public LocalDate current() {
    ZonedDateTime now = ZonedDateTime.now(clock.withZone(zone));
    LocalDate today = now.toLocalDate();
    LocalDate expected =
        DailyIncrementService.expectedLatestTradingDay(
            days.between(Market.US, today.minusDays(45), today), now);
    if (expected != null) {
      return expected;
    }
    // Empty calendar (fresh database, calendar not refreshed yet): at least never write today's
    // unsettled bar.
    // Yesterday may be a weekend here, which is harmless: the broker has no bar for it anyway
    return now.toLocalTime().isBefore(SnapshotWindow.OPENS) ? today.minusDays(1) : today;
  }

  /** Drops bars dated after the cutoff. */
  public static List<DailyBar> settled(List<DailyBar> bars, LocalDate cutoff) {
    return bars.stream().filter(b -> !b.tradeDate().isAfter(cutoff)).toList();
  }
}
