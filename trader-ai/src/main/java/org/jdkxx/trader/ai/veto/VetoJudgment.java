package org.jdkxx.trader.ai.veto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

/**
 * The model's structured judgment of one entry-signal candidate (the schema for the Responses API
 * structured output is generated from this class). <b>Changing any field or description requires a
 * new prompt version</b> ({@link Prompts#VERSION}): conclusions from different versions are not
 * comparable. There are deliberately no fields for target price, position size or buy/sell
 * instructions: all numbers are computed by code, and the model only provides enums and text.
 */
@JsonClassDescription("对一条已由规则引擎产生的入场信号候选的第二意见")
public record VetoJudgment(
    @JsonPropertyDescription(
            "总体立场。BULLISH 看多；NEUTRAL 中性或证据不足；BEARISH 看空；AVOID 存在应回避的重大风险。拿不准时选 NEUTRAL")
        Stance stance,
    @JsonPropertyDescription("对立场的把握。数据缺口大或多空证据相当时选 LOW") Confidence confidence,
    @JsonPropertyDescription("一句话结论，30~80 字，先写最关键的数字") String summary,
    @JsonPropertyDescription("支持做多的证据，0~5 条；没有就给空数组") List<Evidence> bullEvidence,
    @JsonPropertyDescription("支持看空或回避的证据，0~5 条；没有就给空数组") List<Evidence> bearEvidence,
    @JsonPropertyDescription("输入数据未覆盖、但会实质影响判断的风险，每条一句话，0~5 条") List<String> risks,
    @JsonPropertyDescription("缺失或不可靠的数据（包括不知道近期新闻与财报日期），每条一句话") List<String> dataGaps,
    @JsonPropertyDescription("立场为 BEARISH 或 AVOID 时，说明为什么这条信号不该做（一句话）；其他立场给空字符串")
        String vetoReason) {

  public enum Stance {
    BULLISH,
    NEUTRAL,
    BEARISH,
    AVOID
  }

  public enum Confidence {
    HIGH,
    MEDIUM,
    LOW
  }

  /**
   * One evidence item: it must quote a field and value from the input verbatim; the program checks
   * every item.
   */
  public record Evidence(
      @JsonPropertyDescription(
              "输入 JSON 里的字段路径，用点号与方括号下标，例如 valuation.peStaticPercentile5y 或 financials.quarters[0].revenueYoy")
          String field,
      @JsonPropertyDescription("该字段在输入里的原值，照抄数字，不换算单位、不四舍五入") String value,
      @JsonPropertyDescription("这个数值说明了什么，一句话，不超过 40 字") String point) {}
}
