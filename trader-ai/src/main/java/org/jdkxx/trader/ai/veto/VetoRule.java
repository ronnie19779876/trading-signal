package org.jdkxx.trader.ai.veto;

import java.util.List;

/**
 * 否决规则（用户 2026-09-17 认可，比 entry-v3 只看立场更严）：立场为 BEARISH 或 AVOID、把握不是 LOW、
 * 至少 2 条<b>核对通过</b>的看空证据、给了否决理由——四条同时满足才否决。挡住模型摇摆与编造数字造成的误否决。
 */
public final class VetoRule {

    static final int MIN_VERIFIED_BEAR = 2;

    private VetoRule() {
    }

    public enum Verdict {
        /** 否决 */
        VETO,
        /** 放行（含看空但不满足否决条件的） */
        ALLOW
    }

    public record Decision(Verdict verdict, String reason, int verifiedBear, int unverified) {
    }

    public static Decision decide(VetoJudgment j, List<EvidenceVerifier.Checked> checks) {
        int verifiedBear = (int) checks.stream().filter(c -> c.side() == EvidenceVerifier.Side.BEAR && c.verified()).count();
        int unverified = (int) checks.stream().filter(c -> !c.verified()).count();
        boolean bearish = j.stance() == VetoJudgment.Stance.BEARISH || j.stance() == VetoJudgment.Stance.AVOID;
        if (!bearish) {
            return new Decision(Verdict.ALLOW, "立场 " + j.stance(), verifiedBear, unverified);
        }
        if (j.confidence() == null || j.confidence() == VetoJudgment.Confidence.LOW) {
            return new Decision(Verdict.ALLOW, "立场 " + j.stance() + " 但把握为 " + j.confidence() + "，不否决", verifiedBear, unverified);
        }
        if (verifiedBear < MIN_VERIFIED_BEAR) {
            return new Decision(Verdict.ALLOW, "立场 " + j.stance() + " 但核对通过的看空证据只有 " + verifiedBear + " 条，不否决",
                    verifiedBear, unverified);
        }
        if (j.vetoReason() == null || j.vetoReason().isBlank()) {
            return new Decision(Verdict.ALLOW, "立场 " + j.stance() + " 但没有给否决理由，不否决", verifiedBear, unverified);
        }
        return new Decision(Verdict.VETO, j.vetoReason(), verifiedBear, unverified);
    }
}
