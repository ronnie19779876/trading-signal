package org.jdkxx.trader.app.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.jdkxx.trader.ai.AiProperties;
import org.jdkxx.trader.ai.OpenAiClientFactory;
import org.jdkxx.trader.ai.veto.EvidenceVerifier;
import org.jdkxx.trader.ai.veto.OpenAiVetoClient;
import org.jdkxx.trader.ai.veto.Prompts;
import org.jdkxx.trader.ai.veto.VetoClient;
import org.jdkxx.trader.ai.veto.VetoRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Live test against the real OpenAI API (billed): a real signal input (the output of {@code GET
 * /api/signals/ai-input/{symbol}} saved to a file) goes once through Responses API structured
 * output, printing the conclusion, the verification result, tokens and elapsed time.
 *
 * <pre>
 * OPENAI_API_KEY=… TRADER_AI_PAYLOAD=/path/payload.json ./mvnw -pl trader-app -am test -Dtrader.integration=true -Dtest=AiVetoIT …
 * </pre>
 *
 * A second run with {@code TRADER_AI_MAX_OUTPUT=200} verifies that truncation is classified as
 * TRUNCATED instead of taking incomplete JSON as a conclusion.
 */
@EnabledIfSystemProperty(named = "trader.integration", matches = "true")
class AiVetoIT {

  @Test
  void 真实调用一次() throws Exception {
    String key = IntegrationEnv.env("OPENAI_API_KEY");
    Path payload = Path.of(IntegrationEnv.env("TRADER_AI_PAYLOAD"));
    long maxOutput = Long.parseLong(System.getenv().getOrDefault("TRADER_AI_MAX_OUTPUT", "16000"));
    String effort = System.getenv().getOrDefault("TRADER_AI_EFFORT", "medium");
    AiProperties props =
        new AiProperties(
            key,
            System.getenv().getOrDefault("TRADER_AI_MODEL", "gpt-5.6-sol"),
            null,
            Duration.ofSeconds(180),
            0,
            true,
            20,
            Duration.ofMinutes(15),
            effort,
            maxOutput);
    String input = Files.readString(payload);
    JsonNode inputTree = new ObjectMapper().readTree(input);

    for (int round = 1;
        round <= Integer.parseInt(System.getenv().getOrDefault("TRADER_AI_ROUNDS", "1"));
        round++) {
      VetoClient.Call call =
          new OpenAiVetoClient(new OpenAiClientFactory(props), props)
              .analyze(Prompts.VERSION, input);
      System.out.printf(
          "[IT] round=%d status=%s model=%s latency=%dms in=%d cached=%d out=%d reasoning=%d error=%s%n",
          round,
          call.status(),
          call.model(),
          call.latencyMs(),
          call.inputTokens(),
          call.cachedTokens(),
          call.outputTokens(),
          call.reasoningTokens(),
          call.error());
      if (call.judgment() != null) {
        List<EvidenceVerifier.Checked> checks = EvidenceVerifier.check(inputTree, call.judgment());
        VetoRule.Decision d = VetoRule.decide(call.judgment(), checks);
        System.out.println("[IT] judgment=" + call.judgment());
        checks.forEach(
            c ->
                System.out.println(
                    "[IT] check "
                        + c.side()
                        + " "
                        + c.verified()
                        + " "
                        + c.reason()
                        + " ← "
                        + c.evidence()));
        System.out.println("[IT] decision=" + d);
      } else {
        System.out.println("[IT] raw=" + call.rawOutput());
      }
      assertThat(call.status()).isNotNull();
    }
  }
}
