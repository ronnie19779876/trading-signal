package org.jdkxx.trader.common.text;

/**
 * 日志与页面用的脱敏。账户号、主机名之类的值只允许以脱敏形式出现在输出里。
 */
public final class Masking {

    private Masking() {
    }

    /**
     * 保留开头 {@code keep} 个字符，其余用固定 5 个星号代替（不泄露长度）。
     * 空值返回空串；长度不超过 {@code keep} 的值整体打星。
     */
    public static String mask(String value, int keep) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String v = value.trim();
        if (keep <= 0 || v.length() <= keep) {
            return "*****";
        }
        return v.substring(0, keep) + "*****";
    }
}
