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
 *
 * @param signalVetoEnabled 信号评估作业里是否调用模型做否决（生产开、开发关：两个实例同时开会重复计费）
 * @param dailyCallLimit    每天（美东）最多调用次数，含手工调用；超出记 SKIPPED_BUDGET
 * @param jobBudget         一次评估作业里 AI 部分的总时长上限，超出的候选按没有结论放行
 * @param reasoningEffort   推理强度（none / minimal / low / medium / high / xhigh / max）
 * @param maxOutputTokens   输出上限，推理 token 也算在内；给小了会截断
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
