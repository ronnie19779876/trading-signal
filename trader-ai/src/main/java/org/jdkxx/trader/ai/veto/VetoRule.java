package org.jdkxx.trader.ai.veto;

import java.util.List;
import java.util.Locale;

/**
 * Veto rule (approved by the user on 2026-09-17; stricter than entry-v3, which looked only at the
 * stance): stance BEARISH or AVOID, confidence not LOW, at least 2 <b>verified</b> bearish evidence
 * items, and a veto reason given. Only when all four hold is the signal vetoed. This guards against
 * false vetoes caused by the model's stance swinging between runs on the same input and by
 * fabricated numbers.
 *
 * <p>"2 items" means 2 distinct input fields (since 3.5.2): the model citing the same field twice,
 * or with a different case, padding or a zero-padded index, is still one data point. Before 3.5.2
 * the list entries were counted, so a single verified number could satisfy the gate (found in the
 * 2026-10-31 review).
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

  /**
   * @param verifiedBear number of distinct input fields among the verified bearish evidence (not
   *     the number of list entries)
   * @param unverified number of evidence entries, on either side, that failed verification
   */
  public record Decision(Verdict verdict, String reason, int verifiedBear, int unverified) {}

  public static Decision decide(VetoJudgment j, List<EvidenceVerifier.Checked> checks) {
    List<EvidenceVerifier.Checked> bear =
        checks.stream()
            .filter(c -> c.side() == EvidenceVerifier.Side.BEAR && c.verified())
            .toList();
    int verifiedBear =
        (int) bear.stream().map(c -> canonicalField(c.evidence().field())).distinct().count();
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
          "立场 "
              + j.stance()
              + " 但核对通过的看空证据只有 "
              + verifiedBear
              + " 个字段"
              + (bear.size() > verifiedBear ? "（去重前 " + bear.size() + " 条）" : "")
              + "，不否决",
          verifiedBear,
          unverified);
    }
    if (j.vetoReason() == null || j.vetoReason().isBlank()) {
      return new Decision(
          Verdict.ALLOW, "立场 " + j.stance() + " 但没有给否决理由，不否决", verifiedBear, unverified);
    }
    return new Decision(Verdict.VETO, j.vetoReason(), verifiedBear, unverified);
  }

  /**
   * Field path in a canonical form for counting distinct evidence: whitespace removed, lower-cased,
   * leading zeros of array indexes dropped ({@code quarters[00]} = {@code quarters[0]}). Only used
   * for de-duplication; verification itself resolves the path exactly as written.
   */
  static String canonicalField(String field) {
    if (field == null) {
      return "";
    }
    return field.replaceAll("\\s+", "").toLowerCase(Locale.ROOT).replaceAll("\\[0+(\\d)", "[$1");
  }
}
