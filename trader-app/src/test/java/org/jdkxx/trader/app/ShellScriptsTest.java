package org.jdkxx.trader.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Guards the operations scripts against a bash 3.2 parsing trap.
 *
 * <p>macOS ships {@code /bin/bash} 3.2, and {@code #!/usr/bin/env bash} resolves to it on a Mac
 * without Homebrew bash. Under a UTF-8 locale bash 3.2 treats the bytes of a full-width character
 * written right after an unbraced variable as part of the name: {@code "HTTP $code：$url"} expands
 * {@code $code\xef\xbc...}, which under {@code set -u} aborts with "unbound variable". Measured
 * 2026-10-31: {@code check-daily.sh} died on its first non-200 response and {@code run-local.sh}
 * died on every start and stop. CLAUDE.md first recorded the trap for the replica scripts
 * (2026-10-25). In the C locale the same line works, which is why the scripts looked fine.
 *
 * <p>The rule is therefore: a variable followed directly by a non-ASCII character must be written
 * {@code ${var}}. The test only skips when the scripts tree is absent (module built on its own); if
 * the tree exists but no scripts are found, the scan itself is broken and the test fails.
 */
class ShellScriptsTest {

  private static final Path ROOT = Path.of("..");

  /**
   * {@code $name} immediately followed by a non-ASCII character (the trap); {@code ${name}} is
   * fine.
   */
  private static final Pattern UNBRACED =
      Pattern.compile("\\$[A-Za-z_][A-Za-z0-9_]*(?=[^\\x00-\\x7F])");

  @Test
  void 变量后紧跟非ASCII字符时必须加花括号() throws IOException {
    Assumptions.assumeTrue(Files.isDirectory(ROOT.resolve("scripts")), "脚本目录不在（模块单独构建）");
    List<Path> scripts = new ArrayList<>();
    for (String dir : List.of("scripts", "deploy/bin")) {
      try (Stream<Path> files = Files.walk(ROOT.resolve(dir))) {
        files.filter(p -> p.toString().endsWith(".sh")).forEach(scripts::add);
      }
    }
    assertThat(scripts).as("一个脚本都没扫到，说明扫描本身失效了（目录挪了？）").hasSizeGreaterThan(10);

    List<String> offenders = new ArrayList<>();
    for (Path script : scripts) {
      List<String> lines = Files.readAllLines(script, StandardCharsets.UTF_8);
      for (int i = 0; i < lines.size(); i++) {
        Matcher m = UNBRACED.matcher(lines.get(i));
        while (m.find()) {
          offenders.add(ROOT.relativize(script) + ":" + (i + 1) + " " + m.group());
        }
      }
    }
    assertThat(offenders)
        .as("bash 3.2 在 UTF-8 下会把紧跟的全角字符当成变量名的一部分（set -u 下直接退出），改写成 ${var}")
        .isEmpty();
  }
}
