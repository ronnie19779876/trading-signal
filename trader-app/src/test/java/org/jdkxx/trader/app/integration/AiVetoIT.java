package org.jdkxx.trader.app.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdkxx.trader.ai.AiProperties;
import org.jdkxx.trader.ai.OpenAiClientFactory;
import org.jdkxx.trader.ai.veto.EvidenceVerifier;
import org.jdkxx.trader.ai.veto.Prompts;
import org.jdkxx.trader.ai.veto.OpenAiVetoClient;
import org.jdkxx.trader.ai.veto.VetoClient;
import org.jdkxx.trader.ai.veto.VetoRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对真实 OpenAI 的实测（会计费）：一份真实的信号输入（{@code GET /api/signals/ai-input/{symbol}} 的输出存成文件）
 * 走一遍 Responses API 结构化输出，打印结论、核对结果、token 与耗时。
 *
 * <pre>
 * OPENAI_API_KEY=… TRADER_AI_PAYLOAD=/path/payload.json ./mvnw -pl trader-app -am test -Dtrader.integration=true -Dtest=AiVetoIT …
 * </pre>
 * 另跑一次 {@code TRADER_AI_MAX_OUTPUT=200}，验证截断被归类为 TRUNCATED 而不是把残缺 JSON 当结论。
 */
@EnabledIfSystemProperty(named = "trader.integration", matches = "true")
class AiVetoIT {

    @Test
    void 真实调用一次() throws Exception {
        String key = IntegrationEnv.env("OPENAI_API_KEY");
        Path payload = Path.of(IntegrationEnv.env("TRADER_AI_PAYLOAD"));
        long maxOutput = Long.parseLong(System.getenv().getOrDefault("TRADER_AI_MAX_OUTPUT", "16000"));
        String effort = System.getenv().getOrDefault("TRADER_AI_EFFORT", "medium");
        AiProperties props = new AiProperties(key, System.getenv().getOrDefault("TRADER_AI_MODEL", "gpt-5.6-sol"), null,
                Duration.ofSeconds(180), 0, true, 20, Duration.ofMinutes(15), effort, maxOutput);
        String input = Files.readString(payload);
        JsonNode inputTree = new ObjectMapper().readTree(input);

        for (int round = 1; round <= Integer.parseInt(System.getenv().getOrDefault("TRADER_AI_ROUNDS", "1")); round++) {
            VetoClient.Call call = new OpenAiVetoClient(new OpenAiClientFactory(props), props).analyze(Prompts.VERSION, input);
            System.out.printf("[IT] round=%d status=%s model=%s latency=%dms in=%d cached=%d out=%d reasoning=%d error=%s%n",
                    round, call.status(), call.model(), call.latencyMs(), call.inputTokens(), call.cachedTokens(), call.outputTokens(),
                    call.reasoningTokens(), call.error());
            if (call.judgment() != null) {
                List<EvidenceVerifier.Checked> checks = EvidenceVerifier.check(inputTree, call.judgment());
                VetoRule.Decision d = VetoRule.decide(call.judgment(), checks);
                System.out.println("[IT] judgment=" + call.judgment());
                checks.forEach(c -> System.out.println("[IT] check " + c.side() + " " + c.verified() + " " + c.reason() + " ← " + c.evidence()));
                System.out.println("[IT] decision=" + d);
            } else {
                System.out.println("[IT] raw=" + call.rawOutput());
            }
            assertThat(call.status()).isNotNull();
        }
    }
}
