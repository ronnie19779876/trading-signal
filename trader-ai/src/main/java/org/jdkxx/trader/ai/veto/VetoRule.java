package org.jdkxx.trader.ai.veto;

import java.util.List;

/**
 * Veto rule (approved by the user on 2026-09-17; stricter than entry-v3, which looked only at the
 * stance): stance BEARISH or AVOID, confidence not LOW, at least 2 <b>verified</b> bearish evidence
 * items, and a veto reason given. Only when all four hold is the signal vetoed. This guards against
 * false vetoes caused by the model's stance swinging between runs on the same input and by
 * fabricated numbers.
 */
public final class VetoRule {

  static final int MIN_VERIFIED_BEAR = 2;

  private VetoRule() {}

  public enum Verdict {
    /** Veto */
    VETO,
    /** Allow (including bearish judgments that do not meet the veto conditions) */
    ALLOW
  }

  public record Decision(Verdict verdict, String reason, int verifiedBear, int unverified) {}

  public static Decision decide(VetoJudgment j, List<EvidenceVerifier.Checked> checks) {
    int verifiedBear =
        (int)
            checks.stream()
                .filter(c -> c.side() == EvidenceVerifier.Side.BEAR && c.verified())
                .count();
    int unverified = (int) checks.stream().filter(c -> !c.verified()).count();
    boolean bearish =
        j.stance() == VetoJudgment.Stance.BEARISH || j.stance() == VetoJudgment.Stance.AVOID;
    if (!bearish) {
      return new Decision(Verdict.ALLOW, "立场 " + j.stance(), verifiedBear, unverified);
    }
    if (j.confidence() == null || j.confidence() == VetoJudgment.Confidence.LOW) {
      return new Decision(
          Verdict.ALLOW,
          "立场 " + j.stance() + " 但把握为 " + j.confidence() + "，不否决",
          verifiedBear,
          unverified);
    }
    if (verifiedBear < MIN_VERIFIED_BEAR) {
      return new Decision(
          Verdict.ALLOW,
          "立场 " + j.stance() + " 但核对通过的看空证据只有 " + verifiedBear + " 条，不否决",
          verifiedBear,
          unverified);
    }
    if (j.vetoReason() == null || j.vetoReason().isBlank()) {
      return new Decision(
          Verdict.ALLOW, "立场 " + j.stance() + " 但没有给否决理由，不否决", verifiedBear, unverified);
    }
    return new Decision(Verdict.VETO, j.vetoReason(), verifiedBear, unverified);
  }
}
