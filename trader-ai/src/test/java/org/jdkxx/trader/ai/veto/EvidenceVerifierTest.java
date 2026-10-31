package org.jdkxx.trader.ai.veto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Evidence verification.
 *
 * <p>The focus is on text fields: before 3.1.1, unequal strings fell back to "extract the first
 * number and compare", so text such as dates and period labels <b>starting with the same year</b>
 * was judged "matching" whenever the year was the same. The verifier was useless for such fields,
 * while the AI veto threshold is exactly "at least 2 verified bearish evidence items" (found in the
 * 2026-09-25 full-project review).
 */
class EvidenceVerifierTest {

  private static final ObjectMapper M = new ObjectMapper();

  private static EvidenceVerifier.Checked check(String json, String field, String claimed)
      throws Exception {
    JsonNode input = M.readTree(json);
    return EvidenceVerifier.checkOne(
        input, EvidenceVerifier.Side.BEAR, new VetoJudgment.Evidence(field, claimed, "说明"));
  }

  /** The crux: same year, different period must be judged as not matching. */
  @Test
  void 日期只要年份相同不能算一致() throws Exception {
    assertThat(check("{\"asOf\":\"2026-09-17\"}", "asOf", "2026-03-31").verified())
        .as("2026-09-17 与 2026-03-31 抽出来的第一个数字都是 2026")
        .isFalse();
    assertThat(check("{\"period\":\"2026Q3\"}", "period", "2026Q1").verified()).isFalse();
    assertThat(check("{\"asOf\":\"2026-09-17\"}", "asOf", "2026-09-17").verified()).isTrue();
  }

  /**
   * When the text really is just one number, formatting differences are still allowed (thousands
   * separators, currency signs, percent signs).
   */
  @Test
  void 整串是数字时仍按数值比() throws Exception {
    assertThat(check("{\"cap\":\"1234.50\"}", "cap", "1,234.5").verified()).isTrue();
    assertThat(check("{\"pct\":\"12.5\"}", "pct", "12.5%").verified()).isTrue();
    assertThat(check("{\"cap\":\"1234.50\"}", "cap", "999").verified()).isFalse();
  }

  /**
   * Numeric nodes stay lenient: the original value is a number anyway, so extracting a number from
   * the quote to compare is reasonable.
   */
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
