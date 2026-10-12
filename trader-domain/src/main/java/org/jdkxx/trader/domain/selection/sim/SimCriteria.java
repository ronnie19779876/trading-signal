package org.jdkxx.trader.domain.selection.sim;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 「稳定盈利」判据（ARCHITECTURE §26.8，用户 2026-10-13 定，看结果之前定下）。三条同时满足才算：
 * <ol>
 *   <li>年化 ≥ 6%：年化 = (期末权益 ÷ 初始资金)^(365 ÷ 天数) − 1，天数从第一笔入场日算到期末；</li>
 *   <li>跑赢 SPY 同期：账户累计收益 &gt; SPY 同期收益（第一笔入场日开盘 → 期末收盘），打平不算跑赢；</li>
 *   <li>观察期满 12 个月：期末不早于第一笔入场日的一年后（按日历年，闰年也对）。</li>
 * </ol>
 * 未满 12 个月时前两条照算，结论固定为「观察中」。纯函数，不取数。
 *
 * @param days            第一笔入场日到期末经过的天数（入场当天为 0）
 * @param annualized      年化收益；天数为 0 时无法年化，为 null
 * @param beatSpy         SPY 同期收益缺失时为 null（无法判断）
 * @param observationEnds 满 12 个月的那一天
 */
public record SimCriteria(LocalDate start, LocalDate asOf, Long days, Double accountReturn, Double annualized,
                          Double spyReturn, Boolean annualizedMet, Boolean beatSpy, boolean observationMet,
                          LocalDate observationEnds, Verdict verdict) {

    /** 及格线：年化 6%（目标 6～8%） */
    public static final double MIN_ANNUALIZED = 0.06;
    /** 比较用的浮点容差：恰好等于及格线算过，与 SPY 打平不算跑赢 */
    static final double EPS = 1e-9;

    public enum Verdict {
        /** 还没有任何入场 */
        NOT_STARTED,
        /** 未满 12 个月，不下结论 */
        OBSERVING,
        PASS,
        FAIL,
        /** 满 12 个月但缺权益或 SPY 数据，判断不了 */
        UNDETERMINED
    }

    /**
     * @param start     第一笔入场日；没有为 null
     * @param asOf      期末（逐日权益的那一天）
     * @param initial   初始资金
     * @param equity    期末权益（收盘后，现金 + 持仓）；缺失为 null
     * @param spyReturn SPY 同期收益；缺失为 null
     */
    public static SimCriteria evaluate(LocalDate start, LocalDate asOf, double initial, Double equity, Double spyReturn) {
        if (start == null || asOf == null) {
            return new SimCriteria(start, asOf, null, null, null, null, null, null, false, null, Verdict.NOT_STARTED);
        }
        long days = ChronoUnit.DAYS.between(start, asOf);
        LocalDate ends = start.plusYears(1);
        boolean observed = !asOf.isBefore(ends);
        Double account = equity == null || initial <= 0 ? null : equity / initial - 1;
        Double annualized = account == null || days <= 0 ? null : Math.pow(equity / initial, 365.0 / days) - 1;
        Boolean annualizedMet = annualized == null ? null : annualized >= MIN_ANNUALIZED - EPS;
        // 打平不算跑赢；同样留浮点容差，否则 550000 ÷ 500000 − 1 = 0.10000000000000009 会把打平判成跑赢
        Boolean beat = account == null || spyReturn == null ? null : account > spyReturn + EPS;
        Verdict v;
        if (!observed) {
            v = Verdict.OBSERVING;
        } else if (annualizedMet == null || beat == null) {
            v = Verdict.UNDETERMINED;
        } else {
            v = annualizedMet && beat ? Verdict.PASS : Verdict.FAIL;
        }
        return new SimCriteria(start, asOf, days, account, annualized, spyReturn, annualizedMet, beat, observed, ends, v);
    }
}
