package org.jdkxx.trader.storage.pgpass;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Third source of the database credential: when {@code spring.datasource.password} is empty, look
 * up the entry for host:port:database:username in {@code $PGPASSFILE} or {@code ~/.pgpass},
 * following libpq's rules.
 *
 * <p>Purpose: the credential never enters the repository and need not be copied to a second place;
 * psql and the app share the same .pgpass. Explicit configuration (environment variable /
 * secrets.yml) still wins; this is only the fallback when that is left empty. Disable it with
 * {@code trader.storage.pgpass.enabled=false}. The value itself is never logged: log lines name
 * only the file, the user and the database.
 *
 * <p>Registered through {@code META-INF/spring.factories}, so it runs during environment
 * preparation, before any bean exists; that is why it logs through the {@link DeferredLogFactory}.
 */
public class PgpassEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

  static final String PROPERTY_SOURCE_NAME = "pgpass";

  private final Log log;

  public PgpassEnvironmentPostProcessor(DeferredLogFactory logFactory) {
    this.log = logFactory.getLog(PgpassEnvironmentPostProcessor.class);
  }

  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    if (!environment.getProperty("trader.storage.pgpass.enabled", Boolean.class, true)) {
      return;
    }
    String password =
        environment.getProperty(
            // secrets-ok: reads a config key, not a literal credential
            "spring.datasource.password");
    if (password != null && !password.isBlank()) {
      return;
    }
    String username = environment.getProperty("spring.datasource.username");
    Optional<JdbcUrl> url = JdbcUrl.parse(environment.getProperty("spring.datasource.url"));
    if (url.isEmpty() || username == null || username.isBlank()) {
      return;
    }

    Path file = pgpassFile();
    Optional<String> found = PgpassFile.lookup(file, url.get(), username);
    if (found.isEmpty()) {
      log.info(
          "spring.datasource.password 为空且 "
              + file
              + " 中没有匹配 "
              + username
              + "@"
              + url.get().database()
              + " 的记录，保持未配置");
      return;
    }
    // We only get here when the configured value is empty, so putting this source first (highest
    // precedence) can only replace that empty value, never an explicitly configured one.
    environment
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                PROPERTY_SOURCE_NAME, Map.of("spring.datasource.password", found.get())));
    log.info(
        "spring.datasource.password 为空，已从 "
            + file
            + " 读取 "
            + username
            + "@"
            + url.get().database()
            + " 的密码");
  }

  /** Same lookup order as libpq: {@code $PGPASSFILE} if set, otherwise {@code ~/.pgpass}. */
  private static Path pgpassFile() {
    String override = System.getenv("PGPASSFILE");
    if (override != null && !override.isBlank()) {
      return Path.of(override);
    }
    return Path.of(System.getProperty("user.home"), ".pgpass");
  }

  @Override
  public int getOrder() {
    // Must run after ConfigDataEnvironmentPostProcessor, which loads application.yml / secrets.yml;
    // otherwise the explicitly configured value would not be visible yet and would look empty.
    return Ordered.LOWEST_PRECEDENCE;
  }
}
