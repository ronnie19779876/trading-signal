package org.jdkxx.trader.ai.veto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.models.responses.StructuredResponseCreateParams;
import org.jdkxx.trader.ai.veto.VetoJudgment.Confidence;
import org.jdkxx.trader.ai.veto.VetoJudgment.Evidence;
import org.jdkxx.trader.ai.veto.VetoJudgment.Stance;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VetoRuleTest {

    private static final JsonNode INPUT = read("""
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

    private static VetoJudgment judgment(Stance stance, Confidence confidence, List<Evidence> bear, String reason) {
        return new VetoJudgment(stance, confidence, "s", List.of(), bear, List.of(), List.of(), reason);
    }

    private static final List<Evidence> TWO_GOOD = List.of(
            new Evidence("valuation.peStaticPercentile5y", "96.4", "估值在 5 年高位"),
            new Evidence("financials.quarters[0].revenueYoy", "-3.5%", "收入同比下滑"));

    @Test
    void 证据按路径与数值核对() {
        assertThat(EvidenceVerifier.checkOne(INPUT, EvidenceVerifier.Side.BEAR, new Evidence("financials.quarters[0].freeCashFlow", "-120,000,000", "")).verified()).isTrue();
        assertThat(EvidenceVerifier.checkOne(INPUT, EvidenceVerifier.Side.BEAR, new Evidence("valuation.peTtm", "48.5", "")).verified()).as("1% 以内").isTrue();
        assertThat(EvidenceVerifier.checkOne(INPUT, EvidenceVerifier.Side.BEAR, new Evidence("valuation.peTtm", "52", "")).verified()).isFalse();
        assertThat(EvidenceVerifier.checkOne(INPUT, EvidenceVerifier.Side.BEAR, new Evidence("valuation.pb", "3", "")).verified()).as("字段不存在").isFalse();
        assertThat(EvidenceVerifier.checkOne(INPUT, EvidenceVerifier.Side.BEAR, new Evidence("financials.quarters[3].revenueYoy", "1", "")).verified()).as("越界").isFalse();
        assertThat(EvidenceVerifier.checkOne(INPUT, EvidenceVerifier.Side.BEAR, new Evidence("meta.sector", "Information Technology", "")).verified()).isTrue();
        assertThat(EvidenceVerifier.checkOne(INPUT, EvidenceVerifier.Side.BEAR, new Evidence("valuation..peTtm", "48.21", "")).verified()).as("畸形路径").isFalse();
    }

    @Test
    void 看空有把握且两条证据核对通过才否决() {
        VetoJudgment j = judgment(Stance.BEARISH, Confidence.MEDIUM, TWO_GOOD, "收入下滑而估值在高位");

        VetoRule.Decision d = VetoRule.decide(j, EvidenceVerifier.check(INPUT, j));

        assertThat(d.verdict()).isEqualTo(VetoRule.Verdict.VETO);
        assertThat(d.verifiedBear()).isEqualTo(2);
    }

    /** 守护：否决的每个条件单独缺失都不能否决。 */
    @Test
    void 任一条件不满足都放行() {
        List<Evidence> oneFake = List.of(TWO_GOOD.get(0), new Evidence("valuation.peTtm", "80", "编造"));
        for (VetoJudgment j : List.of(
                judgment(Stance.NEUTRAL, Confidence.HIGH, TWO_GOOD, "x"),
                judgment(Stance.AVOID, Confidence.LOW, TWO_GOOD, "x"),
                judgment(Stance.BEARISH, Confidence.HIGH, oneFake, "x"),
                judgment(Stance.BEARISH, Confidence.HIGH, TWO_GOOD, " "))) {
            assertThat(VetoRule.decide(j, EvidenceVerifier.check(INPUT, j)).verdict()).as(j.toString()).isEqualTo(VetoRule.Verdict.ALLOW);
        }
    }

    /** 实测 SDK：结构化输出类能生成 strict JSON Schema（本地校验，不发请求、不需要密钥）。 */
    @Test
    void 输出结构能通过SDK的本地schema校验() {
        StructuredResponseCreateParams<VetoJudgment> params = StructuredResponseCreateParams.<VetoJudgment>builder()
                .model("gpt-5.6-sol")
                .instructions(Prompts.load(Prompts.VERSION))
                .input("{}")
                .text(VetoJudgment.class)
                .store(false)
                .build();

        assertThat(params.rawParams().text().orElseThrow().format().orElseThrow().isJsonSchema()).isTrue();
        // 生成的 schema 实测为 strict：所有字段 required、additionalProperties=false、枚举与嵌套 Evidence 走 $defs
        assertThat(params.rawParams().text().orElseThrow().format().orElseThrow().asJsonSchema().strict()).contains(true);
    }
}
