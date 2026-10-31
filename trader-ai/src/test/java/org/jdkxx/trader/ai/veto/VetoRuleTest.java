package org.jdkxx.trader.ai.veto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.models.responses.StructuredResponseCreateParams;
import java.util.List;
import org.jdkxx.trader.ai.veto.VetoJudgment.Confidence;
import org.jdkxx.trader.ai.veto.VetoJudgment.Evidence;
import org.jdkxx.trader.ai.veto.VetoJudgment.Stance;
import org.junit.jupiter.api.Test;

class VetoRuleTest {

  private static final JsonNode INPUT =
      read(
          """
            {"valuation": {"peStaticPercentile5y": 96.4, "peTtm": 48.21},
             "financials": {"quarters": [{"revenueYoy": -3.5, "freeCashFlow": -120000000}]},
             "meta": {"sector": "Information Technology"}}""");

  private static JsonNode read(String json) {
    try {
      return new ObjectMapper().readTree(json);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static VetoJudgment judgment(
      Stance stance, Confidence confidence, List<Evidence> bear, String reason) {
    return new VetoJudgment(stance, confidence, "s", List.of(), bear, List.of(), List.of(), reason);
  }

  private static final List<Evidence> TWO_GOOD =
      List.of(
          new Evidence("valuation.peStaticPercentile5y", "96.4", "估值在 5 年高位"),
          new Evidence("financials.quarters[0].revenueYoy", "-3.5%", "收入同比下滑"));

  @Test
  void 证据按路径与数值核对() {
    assertThat(
            EvidenceVerifier.checkOne(
                    INPUT,
                    EvidenceVerifier.Side.BEAR,
                    new Evidence("financials.quarters[0].freeCashFlow", "-120,000,000", ""))
                .verified())
        .isTrue();
    assertThat(
            EvidenceVerifier.checkOne(
                    INPUT, EvidenceVerifier.Side.BEAR, new Evidence("valuation.peTtm", "48.5", ""))
                .verified())
        .as("1% 以内")
        .isTrue();
    assertThat(
            EvidenceVerifier.checkOne(
                    INPUT, EvidenceVerifier.Side.BEAR, new Evidence("valuation.peTtm", "52", ""))
                .verified())
        .isFalse();
    assertThat(
            EvidenceVerifier.checkOne(
                    INPUT, EvidenceVerifier.Side.BEAR, new Evidence("valuation.pb", "3", ""))
                .verified())
        .as("字段不存在")
        .isFalse();
    assertThat(
            EvidenceVerifier.checkOne(
                    INPUT,
                    EvidenceVerifier.Side.BEAR,
                    new Evidence("financials.quarters[3].revenueYoy", "1", ""))
                .verified())
        .as("越界")
        .isFalse();
    assertThat(
            EvidenceVerifier.checkOne(
                    INPUT,
                    EvidenceVerifier.Side.BEAR,
                    new Evidence("meta.sector", "Information Technology", ""))
                .verified())
        .isTrue();
    assertThat(
            EvidenceVerifier.checkOne(
                    INPUT,
                    EvidenceVerifier.Side.BEAR,
                    new Evidence("valuation..peTtm", "48.21", ""))
                .verified())
        .as("畸形路径")
        .isFalse();
  }

  @Test
  void 看空有把握且两条证据核对通过才否决() {
    VetoJudgment j = judgment(Stance.BEARISH, Confidence.MEDIUM, TWO_GOOD, "收入下滑而估值在高位");

    VetoRule.Decision d = VetoRule.decide(j, EvidenceVerifier.check(INPUT, j));

    assertThat(d.verdict()).isEqualTo(VetoRule.Verdict.VETO);
    assertThat(d.verifiedBear()).isEqualTo(2);
  }

  /**
   * The "at least 2 verified bearish items" gate counts distinct input fields, not list entries:
   * before 3.5.2 the model citing one field twice (or with a different case / padded index) passed
   * the gate on a single data point (found in the 2026-10-31 review).
   */
  @Test
  void 同一字段重复引用只算一条证据() {
    for (List<Evidence> bear :
        List.of(
            List.of(TWO_GOOD.get(0), TWO_GOOD.get(0)),
            List.of(
                TWO_GOOD.get(0), new Evidence(" valuation.peStaticPercentile5y ", "96.4", "再说一遍")),
            List.of(
                TWO_GOOD.get(1),
                new Evidence("financials.quarters[00].revenueYoy", "-3.5", "同一季")))) {
      VetoJudgment j = judgment(Stance.BEARISH, Confidence.HIGH, bear, "x");
      VetoRule.Decision d = VetoRule.decide(j, EvidenceVerifier.check(INPUT, j));
      assertThat(d.verdict()).as(bear.toString()).isEqualTo(VetoRule.Verdict.ALLOW);
      assertThat(d.verifiedBear()).as(bear.toString()).isEqualTo(1);
    }
  }

  /**
   * A hallucinated index too large for an int used to throw NumberFormatException out of the
   * verifier, so the whole paid call was recorded as INVALID; it is just an unresolvable path.
   */
  @Test
  void 超大下标判为找不到而不是抛异常() {
    assertThat(
            EvidenceVerifier.checkOne(
                    INPUT,
                    EvidenceVerifier.Side.BEAR,
                    new Evidence("financials.quarters[99999999999].revenueYoy", "1", ""))
                .verified())
        .isFalse();
  }

  /** Guard: missing any single veto condition on its own must prevent a veto. */
  @Test
  void 任一条件不满足都放行() {
    List<Evidence> oneFake = List.of(TWO_GOOD.get(0), new Evidence("valuation.peTtm", "80", "编造"));
    for (VetoJudgment j :
        List.of(
            judgment(Stance.NEUTRAL, Confidence.HIGH, TWO_GOOD, "x"),
            judgment(Stance.AVOID, Confidence.LOW, TWO_GOOD, "x"),
            judgment(Stance.BEARISH, Confidence.HIGH, oneFake, "x"),
            judgment(Stance.BEARISH, Confidence.HIGH, TWO_GOOD, " "))) {
      assertThat(VetoRule.decide(j, EvidenceVerifier.check(INPUT, j)).verdict())
          .as(j.toString())
          .isEqualTo(VetoRule.Verdict.ALLOW);
    }
  }

  /**
   * Exercises the real SDK: the structured-output class can generate a strict JSON Schema
   * (validated locally; no request is sent and no key is needed).
   */
  @Test
  void 输出结构能通过SDK的本地schema校验() {
    StructuredResponseCreateParams<VetoJudgment> params =
        StructuredResponseCreateParams.<VetoJudgment>builder()
            .model("gpt-5.6-sol")
            .instructions(Prompts.load(Prompts.VERSION))
            .input("{}")
            .text(VetoJudgment.class)
            .store(false)
            .build();

    assertThat(params.rawParams().text().orElseThrow().format().orElseThrow().isJsonSchema())
        .isTrue();
    // The generated schema was measured to be strict: every field required,
    // additionalProperties=false, enums and the nested Evidence go through $defs
    assertThat(
            params.rawParams().text().orElseThrow().format().orElseThrow().asJsonSchema().strict())
        .contains(true);
  }
}
