package org.jdkxx.trader.core.selection;

/** 量化选股的作业名（落 job_run.job）。与行情、信号的作业名分开定义。 */
public final class SelectionJobs {

    public static final String BACKFILL = "SELECTION_BACKFILL";
    /** 每日：估值续接 → 新公告补取 → 打分 */
    public static final String DAILY = "SELECTION_DAILY";
    /** 每周：公告日刷新、补取证券类型 */
    public static final String WEEKLY = "SELECTION_WEEKLY";
    /** 手工给某一天打分 */
    public static final String SCORE = "SELECTION_SCORE";
    /** 历史重放 */
    public static final String REPLAY = "SELECTION_REPLAY";

    private SelectionJobs() {
    }
}
