package org.jdkxx.trader.common.ratelimit;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 配置里的限频写法：{@code 次数/窗口}，如 {@code 60/30s}、{@code 15/30s}、{@code 60/10m}。
 */
public record RateLimitSpec(int maxCalls, Duration window) {

    private static final Pattern PATTERN = Pattern.compile("^\\s*(\\d+)\\s*/\\s*(\\d+)\\s*(ms|s|m|h)\\s*$", Pattern.CASE_INSENSITIVE);

    public RateLimitSpec {
        if (maxCalls <= 0) {
            throw new IllegalArgumentException("maxCalls 必须为正数：" + maxCalls);
        }
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window 必须为正：" + window);
        }
    }

    public static RateLimitSpec parse(String text) {
        Matcher m = text == null ? null : PATTERN.matcher(text);
        if (m == null || !m.matches()) {
            throw new IllegalArgumentException("限频写法应为 次数/窗口（如 60/30s、60/10m），收到：" + text);
        }
        long n = Long.parseLong(m.group(2));
        Duration window = switch (m.group(3).toLowerCase(Locale.ROOT)) {
            case "ms" -> Duration.ofMillis(n);
            case "s" -> Duration.ofSeconds(n);
            case "m" -> Duration.ofMinutes(n);
            default -> Duration.ofHours(n);
        };
        return new RateLimitSpec(Integer.parseInt(m.group(1)), window);
    }

    @Override
    public String toString() {
        return maxCalls + "/" + window;
    }
}
