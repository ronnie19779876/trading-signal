package org.jdkxx.trader.core.marketdata;

/**
 * Job names. Each value is the {@code job_run.job} column, so the strings are a persisted contract:
 * health metrics, the daily check and the SKIPPED bookkeeping all look rows up by them.
 */
public final class Jobs {

  public static final String UNIVERSE_SYNC = "UNIVERSE_SYNC";
  public static final String UNIVERSE_REFRESH = "UNIVERSE_REFRESH";
  public static final String DEEP_BACKFILL = "DEEP_BACKFILL";
  public static final String DAILY_INCREMENT = "DAILY_INCREMENT";
  public static final String REHAB_REFRESH = "REHAB_REFRESH";
  public static final String CALENDAR_BACKFILL = "CALENDAR_BACKFILL";
  public static final String VALUATION_SNAPSHOT = "VALUATION_SNAPSHOT";
  public static final String FINANCIALS_REFRESH = "FINANCIALS_REFRESH";

  /**
   * The same-day catch-up check itself (it does not run on the job thread and only writes one run
   * record; the jobs it re-submits are still recorded under their own job names).
   */
  public static final String CATCHUP_CHECK = "CATCHUP_CHECK";

  /** Daily snapshot of the account and its positions (phase 3). */
  public static final String ACCOUNT_SNAPSHOT = "ACCOUNT_SNAPSHOT";

  /** Phase 4: daily evaluation of the entry sentinel (signals, expiry and the paper ledger). */
  public static final String SIGNAL_EVALUATION = "SIGNAL_EVALUATION";

  private Jobs() {}
}
