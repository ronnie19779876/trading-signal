package org.jdkxx.trader.ai.veto;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Prompts: the file name is the version, stored as {@code prompt/<version>.md}. Changing the prompt
 * text or the structure of {@link VetoJudgment} requires a new version, and old version files stay
 * in the repository (historical conclusions must remain traceable to the prompt they were produced
 * with).
 */
public final class Prompts {

  /**
   * v2 (3.4.8): earnings dates now come from the broker's announcement dates ({@code
   * calendarHint}'s lastAnnounced / nextScheduled); an earnings report already published is not an
   * event risk
   */
  public static final String VERSION = "sentinel-veto-v2";

  private Prompts() {}

  public static String load(String version) {
    try (InputStream in = Prompts.class.getResourceAsStream("/prompt/" + version + ".md")) {
      if (in == null) {
        throw new IllegalStateException("找不到提示词 prompt/" + version + ".md");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取提示词失败：" + e.getMessage(), e);
    }
  }
}
