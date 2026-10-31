package org.jdkxx.trader.storage.pgpass;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Parser for libpq's ~/.pgpass: each line is {@code hostname:port:database:username:password}, the
 * first four fields accept the {@code *} wildcard, {@code \:} and {@code \\} are escapes, and lines
 * starting with {@code #} are comments. The first matching line wins, as in libpq.
 *
 * <p>One deliberate difference from libpq: the loopback addresses (localhost / 127.0.0.1 / ::1) are
 * treated as the same host, because when connecting through an SSH tunnel the JDBC URL and the
 * .pgpass entry are often written differently.
 *
 * <p>Smaller differences, not deliberate but harmless in practice: the last field keeps any
 * unescaped colons (libpq stops at the first one), an escaped {@code \*} still acts as a wildcard,
 * a trailing lone backslash is dropped, and the file's permission bits are not checked. A file that
 * cannot be read is treated as "no match".
 */
public final class PgpassFile {

  private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "::1");

  private PgpassFile() {}

  public static Optional<String> lookup(Path file, JdbcUrl url, String username) {
    if (file == null || !Files.isRegularFile(file)) {
      return Optional.empty();
    }
    List<String> lines;
    try {
      lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      return Optional.empty();
    }
    for (String line : lines) {
      if (line.isBlank() || line.startsWith("#")) {
        continue;
      }
      List<String> fields = split(line);
      if (fields.size() < 5) {
        continue;
      }
      if (hostMatches(fields.get(0), url.host())
          && matches(fields.get(1), Integer.toString(url.port()))
          && matches(fields.get(2), url.database())
          && matches(fields.get(3), username)) {
        return Optional.of(fields.get(4));
      }
    }
    return Optional.empty();
  }

  static boolean hostMatches(String pattern, String host) {
    if (matches(pattern, host)) {
      return true;
    }
    return LOOPBACK.contains(pattern) && LOOPBACK.contains(host);
  }

  private static boolean matches(String pattern, String value) {
    return "*".equals(pattern) || pattern.equals(value);
  }

  /**
   * Splits on unescaped colons and removes the escaping backslashes. Only the first four colons
   * split; the fifth field keeps everything after them.
   */
  static List<String> split(String line) {
    List<String> out = new ArrayList<>(5);
    StringBuilder cur = new StringBuilder();
    boolean escaped = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (escaped) {
        cur.append(c);
        escaped = false;
      } else if (c == '\\') {
        escaped = true;
      } else if (c == ':' && out.size() < 4) {
        out.add(cur.toString());
        cur.setLength(0);
      } else {
        cur.append(c);
      }
    }
    out.add(cur.toString());
    return out;
  }
}
