package org.jdkxx.trader.core.marketdata.jobs;

@FunctionalInterface
public interface JobBody {

    /** 返回摘要文本（写进 job_run.summary）。抛异常 → FAILED。 */
    String run(JobContext ctx) throws Exception;
}
