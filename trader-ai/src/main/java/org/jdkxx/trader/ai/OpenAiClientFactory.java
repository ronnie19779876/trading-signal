package org.jdkxx.trader.ai;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;

/**
 * Lazily initialised OpenAI client factory. The key is injected explicitly through Spring
 * configuration instead of letting the SDK read environment variables itself, so "where does the
 * configuration come from" has exactly one answer. The client is built once (double-checked locking
 * on a volatile field) and reused, since it owns an HTTP connection pool.
 */
public class OpenAiClientFactory {

  private final AiProperties properties;
  private volatile OpenAIClient client;

  public OpenAiClientFactory(AiProperties properties) {
    this.properties = properties;
  }

  public AiStatus status() {
    return properties.configured()
        ? new AiStatus(true, properties.model(), "已配置 API key")
        : new AiStatus(false, properties.model(), "未配置 trader.ai.api-key，调用模型时会失败");
  }

  public OpenAIClient client() {
    if (!properties.configured()) {
      throw new IllegalStateException(
          "未配置 trader.ai.api-key（环境变量 OPENAI_API_KEY 或 config/secrets.yml）");
    }
    OpenAIClient c = client;
    if (c == null) {
      synchronized (this) {
        c = client;
        if (c == null) {
          OpenAIOkHttpClient.Builder builder =
              OpenAIOkHttpClient.builder()
                  .apiKey(properties.apiKey())
                  .timeout(properties.timeout())
                  .maxRetries(properties.maxRetries());
          if (properties.baseUrl() != null && !properties.baseUrl().isBlank()) {
            builder.baseUrl(properties.baseUrl());
          }
          client = c = builder.build();
        }
      }
    }
    return c;
  }
}
