package org.jdkxx.trader.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jdkxx.trader.core.account.AccountScheduler;
import org.jdkxx.trader.core.marketdata.MarketDataScheduler;
import org.jdkxx.trader.core.signal.SignalScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.yaml.snakeyaml.Yaml;

/**
 * Placeholders in {@code @Scheduled} must have values in the application.yml inside the jar,
 * otherwise the instance fails at startup.
 *
 * <p>This test was added after the fact: {@code valuation-cron} was only written on the config
 * record's {@code @DefaultValue} and not in the yaml, and the dev instance ({@code
 * schedule-enabled=false}) does not wire the schedulers at all, so no amount of local runs could
 * find it; in production (scheduling on) the instance failed to start. Placeholder resolution does
 * not look at record defaults.
 */
class ScheduledPlaceholdersTest {

  private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^:}]+)(?::[^}]*)?}");

  @Test
  void 调度里用到的占位符在配置里都有值() throws Exception {
    Map<String, Object> flat = flatten(loadYaml());
    List<String> missing = new ArrayList<>();
    for (Method m : scheduledMethods()) {
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

  /**
   * Every scheduler class with {@code @Scheduled}. A new scheduler class must be added here,
   * otherwise its placeholders are not checked.
   */
  private static List<Method> scheduledMethods() {
    List<Method> all = new ArrayList<>();
    for (Class<?> type :
        List.of(MarketDataScheduler.class, AccountScheduler.class, SignalScheduler.class)) {
      all.addAll(List.of(type.getDeclaredMethods()));
    }
    return all;
  }

  private static Map<String, Object> loadYaml() throws Exception {
    try (InputStream in = ScheduledPlaceholdersTest.class.getResourceAsStream("/application.yml")) {
      assertThat(in).as("jar 内 application.yml 必须存在").isNotNull();
      return new Yaml().load(in);
    }
  }

  /**
   * Flattens nested maps into a.b.c form, matching Spring's relaxed binding (maps only, which is
   * enough).
   */
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
