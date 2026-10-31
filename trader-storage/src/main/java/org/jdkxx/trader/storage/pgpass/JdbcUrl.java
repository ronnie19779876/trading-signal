package org.jdkxx.trader.storage.pgpass;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts host / port / database from a PostgreSQL JDBC URL so they can be matched against
 * ~/.pgpass.
 *
 * <p>Only the single-host form is recognised. A bracketed IPv6 host or a multi-host URL does not
 * match the pattern, so {@link #parse} returns empty and the .pgpass fallback is simply skipped. A
 * missing port defaults to 5432, the same default libpq applies when matching the file.
 */
public record JdbcUrl(String host, int port, String database) {

  private static final Pattern PATTERN =
      Pattern.compile("^jdbc:postgresql://([^/:?]+)(?::(\\d+))?/([^?;]+)");

  public static Optional<JdbcUrl> parse(String url) {
    if (url == null) {
      return Optional.empty();
    }
    Matcher m = PATTERN.matcher(url.trim());
    if (!m.find()) {
      return Optional.empty();
    }
    int port = m.group(2) == null ? 5432 : Integer.parseInt(m.group(2));
    return Optional.of(new JdbcUrl(m.group(1), port, m.group(3)));
  }
}
