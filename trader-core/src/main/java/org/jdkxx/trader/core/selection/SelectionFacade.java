package org.jdkxx.trader.core.selection;

import org.jdkxx.trader.core.marketdata.jobs.JobService;
import org.jdkxx.trader.storage.selection.SelectionPriceSeamRepository;

import java.util.List;

/** 量化选股的对外入口（控制器只认它）。作业走共用的 JobService 串行执行，与行情、信号作业不并发。 */
public class SelectionFacade {

    private final JobService jobs;
    private final SelectionTargets targets;
    private final SelectionBackfillService backfill;
    private final SelectionInputsService inputs;
    private final SelectionPriceSeamRepository seams;

    public SelectionFacade(JobService jobs, SelectionTargets targets, SelectionBackfillService backfill,
                           SelectionInputsService inputs, SelectionPriceSeamRepository seams) {
        this.jobs = jobs;
        this.targets = targets;
        this.backfill = backfill;
        this.inputs = inputs;
        this.seams = seams;
    }

    /** 提交回补作业，返回 job_run.id。代码不在范围内时当场报错，不提交。 */
    public long backfill(String trigger, BackfillPart part, List<String> symbols, boolean force) {
        targets.only(symbols);
        return jobs.submit(SelectionJobs.BACKFILL, trigger, ctx -> backfill.run(part, symbols, force, ctx));
    }

    public SelectionInputsService.Report inputs() {
        return inputs.report();
    }

    public List<SelectionPriceSeamRepository.SeamRow> seams(boolean failedOnly) {
        return seams.findAll(failedOnly);
    }
}
