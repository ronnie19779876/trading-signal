package org.jdkxx.trader.storage.pgpass;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 PostgreSQL JDBC URL 里取出 host / port / database，用于匹配 ~/.pgpass。
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
