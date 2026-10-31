package org.jdkxx.trader.ai.veto;

import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseStatus;
import com.openai.models.responses.StructuredResponse;
import com.openai.models.responses.StructuredResponseCreateParams;
import com.openai.models.responses.StructuredResponseOutputMessage;
import org.jdkxx.trader.ai.AiProperties;
import org.jdkxx.trader.ai.OpenAiClientFactory;

/**
 * OpenAI implementation of {@link VetoClient}: asks the model for a signal-veto judgment (Responses
 * API + structured output, {@code store=false}, no web search). Does not throw: every failure is
 * classified into {@link Call#status()}, and the upper layer lets the signal through as "no
 * conclusion".
 *
 * <p>The prompt goes into instructions and the data into input, and neither carries a timestamp:
 * the prefix stays constant so that prompt caching can hit. Truncation must be detected from the
 * response status: a truncated JSON may happen to be syntactically valid.
 */
public class OpenAiVetoClient implements VetoClient {

  private final OpenAiClientFactory factory;
  private final AiProperties props;

  public OpenAiVetoClient(OpenAiClientFactory factory, AiProperties props) {
    this.factory = factory;
    this.props = props;
  }

  @Override
  public String model() {
    return props.model();
  }

  @Override
  public String reasoningEffort() {
    return props.reasoningEffort();
  }

  @Override
  public Call analyze(String promptVersion, String inputJson) {
    long started = System.nanoTime();
    StructuredResponse<VetoJudgment> resp;
    try {
      StructuredResponseCreateParams<VetoJudgment> params =
          StructuredResponseCreateParams.<VetoJudgment>builder()
              .model(props.model())
              .instructions(Prompts.load(promptVersion))
              .input(inputJson)
              .text(VetoJudgment.class)
              .store(false)
              .maxOutputTokens(props.maxOutputTokens())
              .reasoning(
                  Reasoning.builder().effort(ReasoningEffort.of(props.reasoningEffort())).build())
              .build();
      resp = factory.client().responses().create(params);
    } catch (RuntimeException e) {
      return failed(Status.FAILED, e, started);
    }
    Response raw = resp.rawResponse();
    long in = raw.usage().map(u -> u.inputTokens()).orElse(0L);
    long cached = raw.usage().map(u -> u.inputTokensDetails().cachedTokens()).orElse(0L);
    long out = raw.usage().map(u -> u.outputTokens()).orElse(0L);
    long reasoning = raw.usage().map(u -> u.outputTokensDetails().reasoningTokens()).orElse(0L);
    long latency = (System.nanoTime() - started) / 1_000_000;
    String model = raw.model().toString();

    // Order matters: an error or an incomplete response is classified before any output is parsed
    if (raw.error().isPresent()) {
      return new Call(
          Status.FAILED,
          null,
          null,
          model,
          raw.id(),
          in,
          cached,
          out,
          reasoning,
          latency,
          raw.error().get().message());
    }
    if (raw.status().map(s -> s.equals(ResponseStatus.INCOMPLETE)).orElse(false)) {
      String reason =
          raw.incompleteDetails().flatMap(d -> d.reason()).map(Object::toString).orElse("unknown");
      Status status = reason.contains("content_filter") ? Status.REFUSED : Status.TRUNCATED;
      return new Call(
          status,
          null,
          null,
          model,
          raw.id(),
          in,
          cached,
          out,
          reasoning,
          latency,
          "响应不完整：" + reason);
    }
    // The first content part found in a message decides. The structured text is parsed by
    // asOutputText(), so a schema violation surfaces there as an exception and is reported as
    // INVALID
    for (var item : resp.output()) {
      if (!item.isMessage()) {
        continue;
      }
      StructuredResponseOutputMessage<VetoJudgment> message = item.asMessage();
      for (StructuredResponseOutputMessage.Content<VetoJudgment> c : message.content()) {
        if (c.isRefusal()) {
          return new Call(
              Status.REFUSED,
              null,
              c.asRefusal().refusal(),
              model,
              raw.id(),
              in,
              cached,
              out,
              reasoning,
              latency,
              "模型拒答");
        }
        String text = c.rawContent().outputText().map(t -> t.text()).orElse(null);
        try {
          VetoJudgment j = c.asOutputText();
          if (j.stance() == null || j.confidence() == null) {
            return new Call(
                Status.INVALID,
                null,
                text,
                model,
                raw.id(),
                in,
                cached,
                out,
                reasoning,
                latency,
                "缺少 stance 或 confidence");
          }
          return new Call(
              Status.OK, j, text, model, raw.id(), in, cached, out, reasoning, latency, null);
        } catch (RuntimeException e) {
          return new Call(
              Status.INVALID,
              null,
              text,
              model,
              raw.id(),
              in,
              cached,
              out,
              reasoning,
              latency,
              "输出不符合结构：" + e.getMessage());
        }
      }
    }
    return new Call(
        Status.INVALID,
        null,
        null,
        model,
        raw.id(),
        in,
        cached,
        out,
        reasoning,
        latency,
        "响应里没有消息输出");
  }

  private Call failed(Status status, RuntimeException e, long started) {
    return new Call(
        status,
        null,
        null,
        props.model(),
        null,
        0,
        0,
        0,
        0,
        (System.nanoTime() - started) / 1_000_000,
        e.getClass().getSimpleName() + "：" + e.getMessage());
  }
}
