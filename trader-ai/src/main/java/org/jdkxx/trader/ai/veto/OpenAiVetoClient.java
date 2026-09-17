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
 * {@link VetoClient} 的 OpenAI 实现：调用模型做信号否决判断（Responses API + 结构化输出，{@code store=false}，不开联网搜索）。
 * 不抛异常：一切失败都归类进 {@link Call#status()}，由上层按"没有结论"放行。
 *
 * <p>提示词放 instructions、数据放 input，且都不带时间戳：前缀不变，缓存才能命中。
 * 截断必须看响应状态——截断的 JSON 可能恰好语法合法。
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
            StructuredResponseCreateParams<VetoJudgment> params = StructuredResponseCreateParams.<VetoJudgment>builder()
                    .model(props.model())
                    .instructions(Prompts.load(promptVersion))
                    .input(inputJson)
                    .text(VetoJudgment.class)
                    .store(false)
                    .maxOutputTokens(props.maxOutputTokens())
                    .reasoning(Reasoning.builder().effort(ReasoningEffort.of(props.reasoningEffort())).build())
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

        if (raw.error().isPresent()) {
            return new Call(Status.FAILED, null, null, model, raw.id(), in, cached, out, reasoning, latency, raw.error().get().message());
        }
        if (raw.status().map(s -> s.equals(ResponseStatus.INCOMPLETE)).orElse(false)) {
            String reason = raw.incompleteDetails().flatMap(d -> d.reason()).map(Object::toString).orElse("unknown");
            Status status = reason.contains("content_filter") ? Status.REFUSED : Status.TRUNCATED;
            return new Call(status, null, null, model, raw.id(), in, cached, out, reasoning, latency, "响应不完整：" + reason);
        }
        for (var item : resp.output()) {
            if (!item.isMessage()) {
                continue;
            }
            StructuredResponseOutputMessage<VetoJudgment> message = item.asMessage();
            for (StructuredResponseOutputMessage.Content<VetoJudgment> c : message.content()) {
                if (c.isRefusal()) {
                    return new Call(Status.REFUSED, null, c.asRefusal().refusal(), model, raw.id(), in, cached, out, reasoning,
                            latency, "模型拒答");
                }
                String text = c.rawContent().outputText().map(t -> t.text()).orElse(null);
                try {
                    VetoJudgment j = c.asOutputText();
                    if (j.stance() == null || j.confidence() == null) {
                        return new Call(Status.INVALID, null, text, model, raw.id(), in, cached, out, reasoning, latency,
                                "缺少 stance 或 confidence");
                    }
                    return new Call(Status.OK, j, text, model, raw.id(), in, cached, out, reasoning, latency, null);
                } catch (RuntimeException e) {
                    return new Call(Status.INVALID, null, text, model, raw.id(), in, cached, out, reasoning, latency,
                            "输出不符合结构：" + e.getMessage());
                }
            }
        }
        return new Call(Status.INVALID, null, null, model, raw.id(), in, cached, out, reasoning, latency, "响应里没有消息输出");
    }

    private Call failed(Status status, RuntimeException e, long started) {
        return new Call(status, null, null, props.model(), null, 0, 0, 0, 0, (System.nanoTime() - started) / 1_000_000,
                e.getClass().getSimpleName() + "：" + e.getMessage());
    }
}
