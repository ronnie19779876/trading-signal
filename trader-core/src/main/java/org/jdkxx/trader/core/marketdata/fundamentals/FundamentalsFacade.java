package org.jdkxx.trader.core.marketdata.fundamentals;

import java.util.List;
import org.jdkxx.trader.core.marketdata.Jobs;
import org.jdkxx.trader.core.marketdata.jobs.JobService;
import org.jdkxx.trader.core.marketdata.universe.UniverseScope;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;

/** REST-facing fundamentals facade: job triggers plus queries. */
public class FundamentalsFacade {

  private final JobService jobs;
  private final ValuationSnapshotService valuation;
  private final FinancialsRefreshService financials;
  private final FundamentalsQueryService query;
  private final UniverseScope scope;

  public FundamentalsFacade(
      JobService jobs,
      ValuationSnapshotService valuation,
      FinancialsRefreshService financials,
      FundamentalsQueryService query,
      UniverseScope scope) {
    this.jobs = jobs;
    this.valuation = valuation;
    this.financials = financials;
    this.query = query;
    this.scope = scope;
  }

  /**
   * A manual trigger outside the close window is rejected right away (409) without leaving a "no
   * snapshot taken" job record; scheduled and catch-up triggers are submitted as usual and the job
   * body checks the window and skips itself.
   */
  public long refreshValuation(String trigger) {
    if ("MANUAL".equals(trigger) && valuation.asOfDate().isEmpty()) {
      throw new IllegalStateException(ValuationSnapshotService.OUTSIDE);
    }
    return jobs.submit(Jobs.VALUATION_SNAPSHOT, trigger, valuation::run);
  }

  public long refreshFinancials(String trigger, boolean all) {
    return jobs.submit(
        Jobs.FINANCIALS_REFRESH, trigger, all ? financials::runAll : financials::run);
  }

  /** Fills in a new pool member's own statements without waiting for the weekly job. */
  public long refreshFinancialsOf(String trigger, List<InstrumentRow> targets) {
    return jobs.submit(Jobs.FINANCIALS_REFRESH, trigger, ctx -> financials.refresh(targets, ctx));
  }

  public FundamentalsQueryService query() {
    return query;
  }

  public UniverseScope scope() {
    return scope;
  }
}
