package org.jdkxx.trader.core.marketdata.jobs;

/** Execution context of a job: report progress, mark partial failure, observe cancellation. */
public interface JobContext {

  long id();

  void progress(String text);

  /**
   * Marks this run as "partially complete" (something failed but the job carried on); the final
   * status becomes PARTIAL. Reasons from repeated calls are concatenated.
   */
  void partial(String reason);

  boolean cancelled();
}
