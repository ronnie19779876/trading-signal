package org.jdkxx.trader.app;

import org.jdkxx.trader.core.marketdata.MarketDataScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @Scheduled} 里的占位符必须在 jar 内 application.yml 里有值，否则实例启动直接失败。
 *
 * <p>这个测试是补的：{@code valuation-cron} 只写在配置记录的 {@code @DefaultValue} 上、没进 yaml，
 * 而开发实例 {@code schedule-enabled=false} 根本不装配调度器，本地怎么跑都发现不了，
 * 一上生产（调度开着）就起不来。占位符解析不看记录上的默认值。
 */
class ScheduledPlaceholdersTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^:}]+)(?::[^}]*)?}");

    @Test
    void 调度里用到的占位符在配置里都有值() throws Exception {
        Map<String, Object> flat = flatten(loadYaml());
        List<String> missing = new ArrayList<>();
        for (Method m : MarketDataScheduler.class.getDeclaredMethods()) {
            Scheduled s = m.getAnnotation(Scheduled.class);
            if (s == null) {
                continue;
            }
            for (String expr : List.of(s.cron(), s.zone())) {
                Matcher matcher = PLACEHOLDER.matcher(expr);
                while (matcher.find()) {
                    String key = matcher.group(1);
                    boolean hasInlineDefault = matcher.group(0).contains(":");
                    if (!hasInlineDefault && !flat.containsKey(key)) {
                        missing.add(m.getName() + " → " + key);
                    }
                }
            }
        }
        assertThat(missing).as("这些占位符在 application.yml 里没有值，生产（调度开启）会起不来").isEmpty();
    }

    private static Map<String, Object> loadYaml() throws Exception {
        try (InputStream in = ScheduledPlaceholdersTest.class.getResourceAsStream("/application.yml")) {
            assertThat(in).as("jar 内 application.yml 必须存在").isNotNull();
            return new Yaml().load(in);
        }
    }

    /** 把嵌套 map 压成 a.b.c 形式，与 Spring 的松散绑定对齐（只处理 map，够用）。 */
    private static Map<String, Object> flatten(Map<String, Object> src) {
        Map<String, Object> out = new LinkedHashMap<>();
        flattenInto("", src, out);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void flattenInto(String prefix, Map<String, Object> src, Map<String, Object> out) {
        for (Map.Entry<String, Object> e : src.entrySet()) {
            String key = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            if (e.getValue() instanceof Map<?, ?> m) {
                flattenInto(key, (Map<String, Object>) m, out);
            } else {
                out.put(key, e.getValue());
            }
        }
    }
}
