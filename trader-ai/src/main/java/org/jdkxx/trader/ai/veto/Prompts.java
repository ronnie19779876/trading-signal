package org.jdkxx.trader.ai.veto;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 提示词：文件名即版本号，放在 {@code prompt/<版本>.md}。改提示词正文或 {@link VetoJudgment} 的结构都必须升版本，
 * 旧版本文件留在仓库里（历史结论要能对上当时的提示词）。
 */
public final class Prompts {

    public static final String VERSION = "sentinel-veto-v1";

    private Prompts() {
    }

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
