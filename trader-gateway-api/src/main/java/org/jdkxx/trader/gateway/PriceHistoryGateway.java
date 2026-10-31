package org.jdkxx.trader.gateway;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.jdkxx.trader.domain.selection.SplitAdjustedBar;

/**
 * Historical daily bar port (quant selection, ARCHITECTURE §25.6; implemented by IBKR). Read-only.
 *
 * <p>Returns <b>split-adjusted</b> TRADES daily bars (IBKR basis, as of the request day); they
 * never go into {@code daily_bar}. IBKR paces historical data requests (60 per 10 minutes); the
 * implementation does its own rate limiting, so the calling thread may block for minutes. Never
 * call it on an SDK callback thread.
 */
public interface PriceHistoryGateway {

  /**
   * @param conId IBKR contract id (resolve it first with {@link ReferenceDataGateway#lookup})
   * @param from first day (inclusive)
   * @param to last day (inclusive)
   */
  CompletableFuture<List<SplitAdjustedBar>> splitAdjustedDailyBars(
      long conId, LocalDate from, LocalDate to);

  /**
   * Date (US/Eastern trading day) of this contract's earliest TRADES daily bar, fetched the same
   * way as {@link #splitAdjustedDailyBars} (primary exchange, regular trading hours). Ask this
   * before requesting history: when a window lies entirely before the earliest date, IBKR sometimes
   * never answers and the request can only time out (measured 2026-10-17 on SKYD) instead of
   * returning 162 "no data". When the broker says there is no data, the future fails with {@code
   * RequestRejectedException} (162). The reported date can be earlier than the real first bar, so
   * use it only to skip or narrow windows, never as proof that data exists.
   *
   * @param conId IBKR contract id
   */
  CompletableFuture<LocalDate> earliestDailyBar(long conId);
}
