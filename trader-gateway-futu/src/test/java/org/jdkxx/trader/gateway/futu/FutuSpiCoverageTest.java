package org.jdkxx.trader.gateway.futu;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every kind of Futu request that is sent must have a matching {@code onReply_*} in {@link
 * FutuChannel}'s SPI.
 *
 * <p>As recorded in the pitfalls table, a missing registration shows up as a <b>request
 * timeout</b>, not as a compile error (measured: a snapshot of 400 securities takes only 228ms, so
 * a timeout means the callback is not registered). Before 3.1.1, the whole {@code FutuChannelTest}
 * class had a single test ("must not send requests on the reply thread") that never touched the
 * SPI, so this pitfall had no guard at all (found in the 2026-09-25 full-project review).
 *
 * <p>The check works on the source text: the calling side is always written as {@code
 * Xxx.Response.class}, and the SPI side always as {@code onReply_Xxx(FTAPI_Conn client, int
 * nSerialNo, Xxx.Response rsp)}; the two sets are compared. No reflection: the SPI consists of
 * overriding methods in anonymous inner classes, and what we really guard against is "a new request
 * was added but its callback was forgotten", which is exactly what the source text shows.
 */
class FutuSpiCoverageTest {

  private static final Path SRC = Path.of("src/main/java/org/jdkxx/trader/gateway/futu");
  private static final Path CHANNEL = SRC.resolve("FutuChannel.java");

  /** Calling side: the {@code Xxx.Response.class} passed as the request's reply type. */
  private static final Pattern REQUESTED =
      Pattern.compile("\\b([A-Za-z][A-Za-z0-9_]*)\\.Response\\.class\\b");

  /** SPI side: {@code onReply_X(..., Xxx.Response rsp)}, keyed by the parameter type. */
  private static final Pattern REGISTERED =
      Pattern.compile(
          "onReply_[A-Za-z0-9_]+\\s*\\([^)]*?\\b([A-Za-z][A-Za-z0-9_]*)\\.Response\\s+\\w+\\s*\\)");

  @Test
  void 每种请求都注册了对应的onReply回调() throws IOException {
    Set<String> requested = new TreeSet<>();
    try (Stream<Path> files = Files.walk(SRC)) {
      for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
        collect(REQUESTED, Files.readString(f), requested);
      }
    }
    Set<String> registered = new TreeSet<>();
    collect(REGISTERED, Files.readString(CHANNEL), registered);

    assertThat(requested).as("一条请求都没扫到，说明扫描本身失效了（改了调用写法？）").hasSizeGreaterThan(8);
    assertThat(registered).as("一个 onReply_* 都没扫到，说明扫描本身失效了").hasSizeGreaterThan(8);

    Set<String> missing = new LinkedHashSet<>(requested);
    missing.removeAll(registered);
    assertThat(missing)
        .as("这些请求发得出去、回复接不住，表现是请求超时而不是编译错误：在 FutuChannel 的 SPI 里补 onReply_*")
        .isEmpty();
  }

  /**
   * Also check the reverse: a registered callback that nobody uses is dead code, most likely a
   * request that was deleted while its callback was not.
   */
  @Test
  void 没有注册了却无人使用的回调() throws IOException {
    Set<String> requested = new TreeSet<>();
    try (Stream<Path> files = Files.walk(SRC)) {
      for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
        collect(REQUESTED, Files.readString(f), requested);
      }
    }
    Set<String> registered = new TreeSet<>();
    collect(REGISTERED, Files.readString(CHANNEL), registered);

    Set<String> unused = new LinkedHashSet<>(registered);
    unused.removeAll(requested);
    assertThat(unused).as("注册了 onReply_* 但没有任何地方发这种请求").isEmpty();
  }

  private static void collect(Pattern p, String src, Set<String> into) {
    Matcher m = p.matcher(src);
    while (m.find()) {
      into.add(m.group(1));
    }
  }

  /**
   * The scan itself must be able to read the files: with a wrong path the two tests above would
   * compare empty sets with empty sets and stay green forever.
   */
  @Test
  void 扫描路径有效() {
    assertThat(Files.isDirectory(SRC)).as("源码目录 %s 不在，测试需要跟着改", SRC).isTrue();
    assertThat(Files.exists(CHANNEL)).as("%s 不在，测试需要跟着改", CHANNEL).isTrue();
    assertThat(List.of()).isEmpty();
  }
}
