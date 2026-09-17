package org.jdkxx.trader.ai.veto;

/**
 * 模型第二意见的调用端口。上层（core）只认这个接口，{@code com.openai.*} 只出现在实现类 {@link OpenAiVetoClient} 里。
 * 实现不抛异常：一切失败都归类进 {@link Call#status()}。
 */
public interface VetoClient {

    enum Status {
        OK, REFUSED, TRUNCATED, INVALID, FAILED
    }

    /**
     * @param rawOutput 模型原文（结构非法时用于排查）；拒答时为拒答文本
     */
    record Call(Status status, VetoJudgment judgment, String rawOutput, String model, String responseId,
                long inputTokens, long cachedTokens, long outputTokens, long reasoningTokens, long latencyMs,
                String error) {
    }

    String model();

    String reasoningEffort();

    Call analyze(String promptVersion, String inputJson);
}
