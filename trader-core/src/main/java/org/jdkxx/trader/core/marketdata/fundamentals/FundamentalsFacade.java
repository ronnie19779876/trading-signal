package org.jdkxx.trader.core.marketdata.fundamentals;

import org.jdkxx.trader.core.marketdata.Jobs;
import org.jdkxx.trader.core.marketdata.jobs.JobService;
import org.jdkxx.trader.core.marketdata.universe.UniverseScope;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;

import java.util.List;

/** 面向 REST 的基本面门面：作业触发 + 查询。 */
public class FundamentalsFacade {

    private final JobService jobs;
    private final ValuationSnapshotService valuation;
    private final FinancialsRefreshService financials;
    private final FundamentalsQueryService query;
    private final UniverseScope scope;

    public FundamentalsFacade(JobService jobs, ValuationSnapshotService valuation, FinancialsRefreshService financials,
                              FundamentalsQueryService query, UniverseScope scope) {
        this.jobs = jobs;
        this.valuation = valuation;
        this.financials = financials;
        this.query = query;
        this.scope = scope;
    }

    public long refreshValuation(String trigger) {
        return jobs.submit(Jobs.VALUATION_SNAPSHOT, trigger, valuation::run);
    }

    public long refreshFinancials(String trigger, boolean all) {
        return jobs.submit(Jobs.FINANCIALS_REFRESH, trigger, all ? financials::runAll : financials::run);
    }

    /** 池新增成员时补齐它自己的财报，不必等每周作业。 */
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
