package org.jdkxx.trader.domain.signal;

/**
 * Signal suppression after all four gates passed (pure functions). Order: edge, then cooldown, then
 * AI veto. The AI comes last so the model is only called for instruments that are about to become a
 * signal today; calling it for the whole universe every day is not feasible.
 *
 * <ul>
 *   <li><b>Edge</b>: a signal is only emitted when the previous trading day was not "all four gates
 *       passed and not vetoed by the AI". A previous day vetoed by the AI still counts as an edge:
 *       the veto is an external condition and the signal should go through once it is lifted (the
 *       price is that a vetoed instrument asks the AI again the next day);
 *   <li><b>Cooldown</b>: suppressed when fewer than {@code cooldownDays} trading days have passed
 *       since the last signal, to stop repeated triggering at the threshold boundary;
 *   <li><b>AI veto</b>: vetoed when the conclusion is AVOID or BEARISH. The AI only removes signals
 *       and never creates one; the absence of an AI conclusion does not block.
 * </ul>
 */
public final class SignalSuppression {

  private SignalSuppression() {}

  public enum Outcome {
    SIGNAL,
    NO_SIGNAL,
    SUPPRESSED_EDGE,
    SUPPRESSED_COOLDOWN,
    BLOCKED_BY_AI,
    /**
     * Passed edge and cooldown, waiting for the AI conclusion (intermediate state, never persisted
     * as a final result)
     */
    PENDING_AI
  }

  /** State of the previous trading day. */
  public enum PreviousDay {
    /**
     * All four gates passed and not vetoed by the AI (including days suppressed by edge or
     * cooldown)
     */
    PASSED,
    /** All four gates passed but vetoed by the AI */
    BLOCKED_BY_AI,
    /** Not all gates passed, not evaluated, or no record */
    NOT_PASSED
  }

  public enum AiVerdict {
    /** AVOID / BEARISH */
    VETO,
    /** Any other conclusion */
    ALLOW,
    /** Not configured, call failed, budget exhausted, or no conclusion */
    ABSENT
  }

  /**
   * @param tradingDaysSinceLastSignal trading days from the last signal to the evaluation day (0 on
   *     the signal day); null if there has never been a signal
   */
  public static Outcome beforeAi(
      boolean allPassed,
      PreviousDay previous,
      Integer tradingDaysSinceLastSignal,
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
