package org.jdkxx.trader.core.selection;

/**
 * Quant selection job names (stored in job_run.job). Defined separately from market data and signal
 * job names.
 */
public final class SelectionJobs {

  public static final String BACKFILL = "SELECTION_BACKFILL";

  /** Daily: valuation extension, then new-announcement refetch, then scoring */
  public static final String DAILY = "SELECTION_DAILY";

  /** Weekly: announcement date refresh, security type backfill */
  public static final String WEEKLY = "SELECTION_WEEKLY";

  /** Manual scoring of one day */
  public static final String SCORE = "SELECTION_SCORE";

  /** Historical replay */
  public static final String REPLAY = "SELECTION_REPLAY";

  /** Simulated-trading recompute (also run once inside the daily job) */
  public static final String SIM_UPDATE = "SELECTION_SIM";

  /** Historical test run (§30), report only */
  public static final String BACKTEST = "SELECTION_BACKTEST";

  /** Simulated-trading weekly report */
  public static final String SIM_WEEKLY = "SELECTION_SIM_WEEKLY";

  private SelectionJobs() {}
}
