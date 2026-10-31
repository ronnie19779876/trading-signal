package org.jdkxx.trader.core.marketdata;

import java.time.LocalDate;
import org.jdkxx.trader.core.marketdata.bars.FactorMode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration of the market-data foundation ({@code trader.marketdata.*}). Mechanism parameters
 * have defaults in the jar; the switch ({@code schedule-enabled}) lives in the external config.
 *
 * <p>Note on the cron fields: the {@code @DefaultValue}s below are not enough on their own.
 * {@code @Scheduled} placeholders are resolved from the environment and never see record defaults,
 * so every cron also has to be in the in-jar {@code application.yml} (guarded by {@code
 * ScheduledPlaceholdersTest}). All crons run in {@link #zone()}.
 */
@ConfigurationProperties(prefix = "trader.marketdata")
public record MarketDataProperties(
    @DefaultValue Universe universe,
    @DefaultValue Pool pool,
    @DefaultValue Refresh refresh,
    @DefaultValue History history,
    @DefaultValue Adjust adjust,
    @DefaultValue Realtime realtime,
    @DefaultValue Fundamentals fundamentals,
    @DefaultValue("false") boolean scheduleEnabled,
    @DefaultValue("0 30 17 * * MON-FRI") String incrementCron,
    @DefaultValue("0 40 17 * * MON-FRI") String valuationCron,
    @DefaultValue("0 0 7 * * SAT") String financialsCron,
    @DefaultValue("0 0 21 * * MON-FRI") String catchupCron,
    @DefaultValue("America/New_York") String zone) {

  public record Universe(
      @DefaultValue("https://en.wikipedia.org/wiki/List_of_S%26P_500_companies")
          String wikipediaSp500Url,
      @DefaultValue("https://en.wikipedia.org/wiki/List_of_NASDAQ-100_companies")
          String wikipediaNdx100Url,
      @DefaultValue(
              "https://www.ssga.com/us/en/intermediary/library-content/products/fund-data/etfs/us/holdings-daily-us-en-spy.xlsx")
          String spyHoldingsUrl,
      @DefaultValue("true") boolean crossCheckSpy,
      @DefaultValue("0 30 6 * * SAT") String syncCron,
      @DefaultValue("200") int staticBatchSize,
      /**
       * Maximum number of exits allowed in one sync: above it the <b>whole index is skipped</b>,
       * the job is recorded as PARTIAL, and a human has to pass force to let it through. The
       * effective threshold is max(this value, current members × maxRemovalsPercent%). Basis
       * (production database, 2026-09-25): SP500 has 503 members and 0 historical exits; NDX100 has
       * 101 members and 1 historical exit. The goal is not "never remove in bulk" but <b>never
       * remove in bulk silently</b>: a legitimate large change such as the Nasdaq annual
       * reconstitution in December gets blocked once, which is exactly when someone should look.
       */
      @DefaultValue("5") int maxRemovalsPerSync,
      @DefaultValue("5") int maxRemovalsPercent,
      /**
       * Cap for batch-level recovery: when Futu rejects a batch as "unknown stock" and the
       * per-symbol check finds more bad symbols than this, the batch is <b>left alone and nothing
       * is marked</b> (that looks more like a problem on Futu's side; a collection job must not
       * silently downgrade hundreds of instruments). Basis (2026-10-07): in more than a month live
       * with 500+ instruments, "RESOLVED → unknown to Futu" happened once. Newly added constituents
       * go through PENDING, not this path. See {@code UnknownSymbolGuard}.
       */
      @DefaultValue("3") int maxUnknownPerBatch,
      @DefaultValue("trading-signal/1.0 (market data research; contact via repository)")
          String userAgent) {}

  public record Pool(@DefaultValue("50") int maxSize) {}

  /**
   * Subscription rotation: each batch subscribes batchSize instruments (≤ subscription quota −
   * reserve) and stays subscribed for at least holdSeconds before unsubscribing (broker rule: a
   * subscription must be at least 1 minute old). rehabSpreadDays: once the full universe's
   * price-adjustment factors fall due, each increment refreshes at most 1/rehabSpreadDays of the
   * universe (least recently refreshed first); ≤ 1 means no cap.
   */
  public record Refresh(
      @DefaultValue("90") int batchSize,
      @DefaultValue("65") int holdSeconds,
      @DefaultValue("true") boolean universeIncrement,
      @DefaultValue("1000") int fullCount,
      @DefaultValue("5") int overlap,
      @DefaultValue("5") int rehabSpreadDays) {}

  public record History(
      @DefaultValue("2006-01-01") LocalDate from, @DefaultValue("10") int quotaReserve) {}

  public record Adjust(@DefaultValue("PER_EVENT") FactorMode factorMode) {}

  /**
   * Fundamentals. Valuation snapshots cover the full universe once per trading day (400 per call,
   * using neither subscription nor history quota); financial statements cover only the pool and
   * holdings, once a week (the full universe takes 35 minutes and almost none of it is used).
   */
  public record Fundamentals(
      @DefaultValue("true") boolean enabled,
      @DefaultValue("400") int snapshotBatchSize,
      @DefaultValue("12") int financialPeriods,
      @DefaultValue("30d") java.time.Duration profileRefreshAfter,
      @DefaultValue("180d") java.time.Duration financialStaleAfter) {}

  /**
   * Real-time quotes (not persisted). auto-subscribe: false on the dev machine / true in the
   * release package; two instances subscribing at once consume double quota on the same account.
   * pause-during-refresh: pause real-time subscriptions during a rotation (freeing quota for the
   * 90-per-batch rotation) and resume afterwards.
   */
  public record Realtime(
      @DefaultValue("true") boolean enabled,
      @DefaultValue("false") boolean autoSubscribe,
      @DefaultValue("10") int reserveQuota,
      @DefaultValue("true") boolean pauseDuringRefresh,
      @DefaultValue("1s") java.time.Duration streamInterval,
      @DefaultValue("61s") java.time.Duration unsubscribeMinAge) {}
}
