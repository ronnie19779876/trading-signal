package org.jdkxx.trader.core.marketdata.jobs;

/** The body of a batch job, run on the single job thread by {@link JobService}. */
@FunctionalInterface
public interface JobBody {

  /**
   * Returns the summary text (written to job_run.summary). Throwing marks the run FAILED; calling
   * {@link JobContext#partial} along the way marks it PARTIAL.
   */
  String run(JobContext ctx) throws Exception;
}
