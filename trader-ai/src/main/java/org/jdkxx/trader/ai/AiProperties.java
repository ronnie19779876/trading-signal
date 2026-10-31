package org.jdkxx.trader.ai;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Model access configuration ({@code trader.ai.*}).
 *
 * <p>api-key is sensitive configuration: on the server it comes from the environment variable
 * {@code OPENAI_API_KEY} (the in-jar default is {@code ${OPENAI_API_KEY:}}), locally in IDEA from
 * {@code trader.ai.api-key} in {@code config/secrets.yml}. Without it the application still starts
 * and only an actual model call fails: the skeleton and the data pipeline should not be held up by
 * a key.
 *
 * <p>model is persisted together with every conclusion. Judgments from different models are not
 * comparable, so changing the model name must be treated as a change of methodology.
 *
 * @param timeout request timeout of the SDK client per attempt; the SDK may retry up to maxRetries
 *     times, so one call can take considerably longer than this
 * @param signalVetoEnabled whether the signal-evaluation job asks the model for a veto (on in
 *     production, off in development: with both instances on, every call would be billed twice)
 * @param dailyCallLimit maximum calls per (US/Eastern) day, manual calls included; beyond it the
 *     call is recorded as SKIPPED_BUDGET
 * @param jobBudget cap on the total time spent on AI within one evaluation job; candidates beyond
 *     it are let through as having no conclusion
 * @param reasoningEffort reasoning effort (none / minimal / low / medium / high / xhigh / max)
 * @param maxOutputTokens output limit, reasoning tokens included; too small a value truncates the
 *     output
 */
@ConfigurationProperties(prefix = "trader.ai")
public record AiProperties(
    String apiKey,
    @DefaultValue("gpt-5.6-sol") String model,
    String baseUrl,
    @DefaultValue("120s") Duration timeout,
    @DefaultValue("2") Integer maxRetries,
    @DefaultValue("false") boolean signalVetoEnabled,
    @DefaultValue("20") int dailyCallLimit,
    @DefaultValue("15m") Duration jobBudget,
    @DefaultValue("medium") String reasoningEffort,
    @DefaultValue("16000") long maxOutputTokens) {

  public boolean configured() {
    return apiKey != null && !apiKey.isBlank();
  }
}
