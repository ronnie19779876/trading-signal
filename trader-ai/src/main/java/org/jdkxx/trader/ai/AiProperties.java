package org.jdkxx.trader.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 模型接入配置（{@code trader.ai.*}）。
 *
 * <p>api-key 属于敏感配置：服务器走环境变量 {@code OPENAI_API_KEY}（jar 内默认值
 * {@code ${OPENAI_API_KEY:}}），本机 IDEA 走 {@code config/secrets.yml} 的 {@code trader.ai.api-key}。
 * 未配置时应用照常启动，只有真正调用模型时才报错——骨架与数据链路不应被一个 key 卡住。
 *
 * <p>model 会随结论一起落库，不同模型的判断不可比，改模型名要当作口径变更对待。
 */
@ConfigurationProperties(prefix = "trader.ai")
public record AiProperties(
        String apiKey,
        @DefaultValue("gpt-5.6-sol") String model,
        String baseUrl,
        @DefaultValue("120s") Duration timeout,
        @DefaultValue("2") Integer maxRetries) {

    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
