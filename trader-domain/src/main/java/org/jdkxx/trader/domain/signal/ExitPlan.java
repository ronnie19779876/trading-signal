package org.jdkxx.trader.domain.signal;

/**
 * Exit plan (not a gate): initial stop, +1R reference price, chandelier stop, time stop and the
 * overhead resistance zone as a reference target. Following the paired tests in the write-up, it
 * <b>does not include taking half off at +1R</b> (on two independent samples, halving was
 * significantly worse: it truncates the fat-tailed gains).
 *
 * @param initialStop initial stop (computed by the fourth gate)
 * @param riskPerShare unit risk R = evaluation-day close − stop
 * @param plusOneR evaluation-day close + R, the reference for the time stop
 * @param chandelierStop 22-day high − 3×ATR14 (evaluation-day basis; afterwards recomputed daily
 *     and only raised)
 * @param timeStopDays exit if +1R has not been reached within this many trading days
 * @param targetZone nearest resistance zone above (cluster of fractal highs), null if none
 * @param target bottom of the nearest resistance zone above, null if none
 * @param rewardRisk (target − close) ÷ R, for reference only; no up-front reward/risk gate
 */
public record ExitPlan(
    double initialStop,
    double riskPerShare,
    double plusOneR,
    double chandelierStop,
    int timeStopDays,
    PriceZone targetZone,
    Double target,
    Double rewardRisk) {}
