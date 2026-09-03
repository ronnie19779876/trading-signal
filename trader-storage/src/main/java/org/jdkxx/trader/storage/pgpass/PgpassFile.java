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
 * libpq 的 ~/.pgpass 解析：每行 {@code hostname:port:database:username:password}，
 * 前四段支持 {@code *} 通配，{@code \:} 与 {@code \\} 是转义，{@code #} 开头为注释。
 *
 * <p>与 libpq 的一个刻意差异：本地回环地址（localhost / 127.0.0.1 / ::1）互相视为同一主机，
 * 因为经 SSH 隧道连库时 JDBC URL 与 .pgpass 里写的往往不是同一个写法。
 */
public final class PgpassFile {

    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "::1");

    private PgpassFile() {
    }

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

    /** 按未转义的冒号切分；密码段保留其后的所有内容。 */
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
