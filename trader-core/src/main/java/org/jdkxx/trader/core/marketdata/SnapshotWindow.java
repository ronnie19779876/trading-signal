package org.jdkxx.trader.core.marketdata;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Time window for close-of-day snapshots: from 16:15 US/Eastern on a trading day (closing data has
 * settled) to 04:00 the next morning (pre-market starts and the snapshot price is no longer that
 * day's close). Shared by the account snapshot and the valuation snapshot.
 *
 * <p>Why a window: IBKR only reports positions and cash as of "now", with no history, so positions
 * captured outside the window do not belong to any trading day's close. Futu snapshots carry live
 * prices intraday, so valuations taken outside the window would overwrite the previous trading
 * day's market cap and P/E computed from the close.
 */
public final class SnapshotWindow {

  /**
   * When closing data counts as settled (16:00 close plus 15 minutes for the broker to finalize).
   * The bar write cutoff ({@code SettledCutoff}) uses the same boundary.
   */
  public static final LocalTime OPENS = LocalTime.of(16, 15);

  public static final LocalTime CLOSES = LocalTime.of(4, 0);

  public static final String OUTSIDE = "现在不在账户快照窗口（交易日美东 16:15 至次日 04:00）。盈透只给当前持仓，过去的日子补不回来";

  private SnapshotWindow() {}

  /**
   * Which trading day a snapshot taken now belongs to; empty outside the window.
   *
   * <p>Two cases: at or after 16:15 on a trading day it is today; before 04:00 it is yesterday,
   * provided yesterday was a trading day (so Saturday 01:00 still maps to Friday, while Monday
   * 01:00 maps to nothing). Both checks use the US/Eastern wall clock of {@code nowEt}.
   */
  public static Optional<LocalDate> asOfDate(
      ZonedDateTime nowEt, Predicate<LocalDate> isTradingDay) {
    LocalDate today = nowEt.toLocalDate();
    LocalTime t = nowEt.toLocalTime();
    if (!t.isBefore(OPENS) && isTradingDay.test(today)) {
      return Optional.of(today);
    }
    if (t.isBefore(CLOSES) && isTradingDay.test(today.minusDays(1))) {
      return Optional.of(today.minusDays(1));
    }
    return Optional.empty();
  }
}
