package org.jdkxx.trader.gateway;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.jdkxx.trader.domain.CompanyProfile;
import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.FinancialReport;
import org.jdkxx.trader.domain.FinancialStatement;
import org.jdkxx.trader.domain.HistoryQuota;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.InstrumentStatic;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.RehabFactor;
import org.jdkxx.trader.domain.SubscriptionInfo;
import org.jdkxx.trader.domain.TradingDay;
import org.jdkxx.trader.domain.ValuationSnapshot;

/**
 * Market data port (only Futu implements it in this phase). Every method is read-only or only
 * affects the subscription quota; bars are always unadjusted. There are two channels for fetching
 * bars:
 *
 * <ul>
 *   <li>{@link #historyDailyBars}: the history API, up to 20 years, paging done inside the adapter;
 *       <b>each instrument consumes 1 unit of the rolling 7-day history quota</b> (100 instruments
 *       per 7 days);
 *   <li>{@link #subscribeDailyBars} + {@link #recentDailyBars}: subscribe, then fetch the latest
 *       &lt;= 1000 bars; uses no history quota, only subscription quota.
 * </ul>
 */
public interface MarketDataGateway {

  CompletableFuture<List<InstrumentStatic>> staticInfo(List<Instrument> instruments);

  CompletableFuture<HistoryQuota> historyQuota();

  CompletableFuture<List<DailyBar>> historyDailyBars(
      Instrument instrument, LocalDate from, LocalDate to);

  CompletableFuture<Void> subscribeDailyBars(List<Instrument> instruments);

  /**
   * Unsubscribing is only allowed once a subscription is at least 1 minute old; an earlier call is
   * rejected by the broker.
   */
  CompletableFuture<Void> unsubscribeDailyBars(List<Instrument> instruments);

  CompletableFuture<List<DailyBar>> recentDailyBars(Instrument instrument, int count);

  CompletableFuture<List<RehabFactor>> rehab(Instrument instrument);

  CompletableFuture<List<TradingDay>> tradingDays(Market market, LocalDate from, LocalDate to);

  // ------------------------------------------------------------------ real-time quotes (step 2)

  /**
   * Subscribes to basic quotes and registers for pushes; once subscribed, the broker immediately
   * pushes the current value. Each instrument uses 1 unit of subscription quota.
   */
  CompletableFuture<Void> subscribeQuotes(List<Instrument> instruments);

  /** Unsubscribing is only allowed once a subscription is at least 1 minute old. */
  CompletableFuture<Void> unsubscribeQuotes(List<Instrument> instruments);

  /** Subscription quota summed over all connections (of the same broker account). */
  CompletableFuture<SubscriptionInfo> subscriptionInfo();

  void addQuoteListener(QuoteListener listener);

  // ------------------------------------------------------------------ fundamentals (step 3)

  /**
   * Valuation snapshots; no subscription needed and no subscription or history-bar quota used. At
   * most {@link #SNAPSHOT_BATCH} instruments per call; the caller splits larger sets into batches.
   * Market cap moves with the price, so the time of the call decides whether the numbers are
   * intraday or closing values.
   */
  CompletableFuture<List<ValuationSnapshot>> snapshots(List<Instrument> instruments);

  /**
   * The most recent periods of one statement type for one instrument, ordered by period end from
   * newest to oldest.
   */
  CompletableFuture<List<FinancialReport>> financials(
      Instrument instrument, FinancialStatement statement, int periods);

  CompletableFuture<CompanyProfile> companyProfile(Instrument instrument);

  /** The broker's per-call limit for the snapshot API. */
  int SNAPSHOT_BATCH = 400;
}
