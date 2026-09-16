package org.jdkxx.trader.domain.signal;

/**
 * 四门全过之后的信号抑制（纯函数）。顺序：边沿 → 冷却 → AI 否决。
 * AI 放在最后，是为了只对"当天将成为信号"的标的调用模型——全量标的每天都调不现实。
 *
 * <ul>
 *   <li><b>边沿</b>：只在"上一交易日不是【四门全过且未被 AI 否决】"时发出。上一交易日被 AI 否决的仍算边沿——
 *       否决属外部条件，解除后应放行（代价是被否决的标的次日会再问一次 AI）；</li>
 *   <li><b>冷却</b>：距上一条信号不足 {@code cooldownDays} 个交易日则抑制，防阈值边缘反复触发；</li>
 *   <li><b>AI 否决</b>：结论为 AVOID 或 BEARISH 时否决。AI 只减少信号，不产生信号；没有 AI 结论不阻断。</li>
 * </ul>
 */
public final class SignalSuppression {

    private SignalSuppression() {
    }

    public enum Outcome {
        SIGNAL,
        NO_SIGNAL,
        SUPPRESSED_EDGE,
        SUPPRESSED_COOLDOWN,
        BLOCKED_BY_AI,
        /** 过了边沿与冷却，等 AI 结论（中间态，不落库为最终结果） */
        PENDING_AI
    }

    /** 上一交易日的状态。 */
    public enum PreviousDay {
        /** 四门全过且未被 AI 否决（含当天被边沿、冷却抑制的） */
        PASSED,
        /** 四门全过但被 AI 否决 */
        BLOCKED_BY_AI,
        /** 未全过、不予判定，或没有记录 */
        NOT_PASSED
    }

    public enum AiVerdict {
        /** AVOID / BEARISH */
        VETO,
        /** 其他结论 */
        ALLOW,
        /** 未配置、调用失败、预算用尽、没有结论 */
        ABSENT
    }

    /**
     * @param tradingDaysSinceLastSignal 上一条信号到判定日相隔的交易日数（信号当天为 0）；从未发过信号传 null
     */
    public static Outcome beforeAi(boolean allPassed, PreviousDay previous, Integer tradingDaysSinceLastSignal,
                                   SentinelThresholds th) {
        if (!allPassed) {
            return Outcome.NO_SIGNAL;
        }
        if (previous == PreviousDay.PASSED) {
            return Outcome.SUPPRESSED_EDGE;
        }
        if (tradingDaysSinceLastSignal != null && tradingDaysSinceLastSignal < th.cooldownDays()) {
            return Outcome.SUPPRESSED_COOLDOWN;
        }
        return Outcome.PENDING_AI;
    }

    public static Outcome afterAi(Outcome beforeAi, AiVerdict verdict) {
        if (beforeAi != Outcome.PENDING_AI) {
            return beforeAi;
        }
        return verdict == AiVerdict.VETO ? Outcome.BLOCKED_BY_AI : Outcome.SIGNAL;
    }
}
