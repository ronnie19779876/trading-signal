package org.jdkxx.trader.core.marketdata;

/** 作业名。 */
public final class Jobs {

    public static final String UNIVERSE_SYNC = "UNIVERSE_SYNC";
    public static final String UNIVERSE_REFRESH = "UNIVERSE_REFRESH";
    public static final String DEEP_BACKFILL = "DEEP_BACKFILL";
    public static final String DAILY_INCREMENT = "DAILY_INCREMENT";
    public static final String REHAB_REFRESH = "REHAB_REFRESH";
    public static final String CALENDAR_BACKFILL = "CALENDAR_BACKFILL";
    public static final String VALUATION_SNAPSHOT = "VALUATION_SNAPSHOT";
    public static final String FINANCIALS_REFRESH = "FINANCIALS_REFRESH";
    /** 当天补偿检查本身（不走作业线程，只写一行运行记录；补跑的作业仍记在各自的作业名下）。 */
    public static final String CATCHUP_CHECK = "CATCHUP_CHECK";
    /** 账户与持仓的每日快照（第 3 期）。 */
    public static final String ACCOUNT_SNAPSHOT = "ACCOUNT_SNAPSHOT";

    /** 第 4 期：入场哨兵每日评估（含信号、过期、纸面账本） */
    public static final String SIGNAL_EVALUATION = "SIGNAL_EVALUATION";

    private Jobs() {
    }
}
