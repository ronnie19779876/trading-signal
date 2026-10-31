package org.jdkxx.trader.ai.veto;

/**
 * Port for asking the model for a second opinion. The upper layer (core) only knows this interface;
 * {@code com.openai.*} appears only in the implementation {@link OpenAiVetoClient}. Implementations
 * do not throw: every failure is classified into {@link Call#status()}.
 */
public interface VetoClient {

  enum Status {
    OK,
    REFUSED,
    TRUNCATED,
    INVALID,
    FAILED
  }

  /**
   * Result of one model call, persisted with the analysis row.
   *
   * @param judgment the parsed judgment; non-null only when status is OK
   * @param rawOutput the model's raw text (for troubleshooting when the structure is invalid); the
   *     refusal text on refusal
   */
  record Call(
      Status status,
      VetoJudgment judgment,
      String rawOutput,
      String model,
      String responseId,
      long inputTokens,
      long cachedTokens,
      long outputTokens,
      long reasoningTokens,
      long latencyMs,
      String error) {}

  String model();

  String reasoningEffort();

  Call analyze(String promptVersion, String inputJson);
}
