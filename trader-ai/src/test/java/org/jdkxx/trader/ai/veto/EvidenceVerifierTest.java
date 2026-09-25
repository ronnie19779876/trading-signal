package org.jdkxx.trader.ai.veto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 证据核对。
 *
 * <p>重点是文本字段：3.1.1 前字符串不等时会回落到「抽出第一个数字比大小」，
 * 于是日期、期别这类<b>以同一个年份开头</b>的文本只要年份相同就判「一致」——
 * 核对器对这类字段形同虚设，而 AI 否决的门槛正是「≥2 条核对通过的看空证据」
 * （2026-09-25 全项目审查发现）。
 */
class EvidenceVerifierTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static EvidenceVerifier.Checked check(String json, String field, String claimed) throws Exception {
        JsonNode input = M.readTree(json);
        return EvidenceVerifier.checkOne(input, EvidenceVerifier.Side.BEAR,
                new VetoJudgment.Evidence(field, claimed, "说明"));
    }

    /** 要害：同年不同期，必须判不一致。 */
    @Test
    void 日期只要年份相同不能算一致() throws Exception {
        assertThat(check("{\"asOf\":\"2026-09-17\"}", "asOf", "2026-03-31").verified())
                .as("2026-09-17 与 2026-03-31 抽出来的第一个数字都是 2026").isFalse();
        assertThat(check("{\"period\":\"2026Q3\"}", "period", "2026Q1").verified()).isFalse();
        assertThat(check("{\"asOf\":\"2026-09-17\"}", "asOf", "2026-09-17").verified()).isTrue();
    }

    /** 文本里真的就是一个数时，仍然允许格式差异（千分位、货币符号、百分号）。 */
    @Test
    void 整串是数字时仍按数值比() throws Exception {
        assertThat(check("{\"cap\":\"1234.50\"}", "cap", "1,234.5").verified()).isTrue();
        assertThat(check("{\"pct\":\"12.5\"}", "pct", "12.5%").verified()).isTrue();
        assertThat(check("{\"cap\":\"1234.50\"}", "cap", "999").verified()).isFalse();
    }

    /** 数字节点照旧宽松：原值本来就是数，从引用里抽一个数来比是合理的。 */
    @Test
    void 数字字段照旧按数值比() throws Exception {
        assertThat(check("{\"pe\":18.4}", "pe", "约 18.4 倍").verified()).isTrue();
        assertThat(check("{\"pe\":18.4}", "pe", "25").verified()).isFalse();
    }

    @Test
    void 字段不存在或为空都判不通过() throws Exception {
        assertThat(check("{\"a\":1}", "b", "1").reason()).contains("没有字段");
        assertThat(check("{\"a\":null}", "a", "1").reason()).contains("为空");
        assertThat(check("{\"a\":1}", "", "1").reason()).contains("没有字段路径");
    }

    @Test
    void 大小写不同的文本算一致() throws Exception {
        assertThat(check("{\"trend\":\"BEARISH\"}", "trend", "bearish").verified()).isTrue();
    }
}
