package org.jdkxx.trader.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * "Delete, then insert" writes must run in a transaction.
 *
 * <p>{@code FinancialRepository.upsertAll} once missed this: replacing a period first deletes from
 * {@code financial_item} and then batch-inserts. Without a transaction a failed batch insert leaves
 * the period row in {@code financial_report} with an empty {@code financial_item}, an empty shell
 * of a period; and since the fundamentals audit judges report freshness by {@code period_end} only,
 * the shell even makes it believe "this period is already here", and nobody notices (found in the
 * 2026-09-25 full-project review).
 *
 * <p>The check works on source text: a method body that contains both DELETE and INSERT /
 * batchUpdate must carry {@code @Transactional}. Reflection cannot see this, because method bodies
 * are outside its reach.
 *
 * <p>Limits: the pattern relies on the 2-space (google-java-format) indentation of this module, on
 * annotations sitting directly above the method, and only looks inside a single public method, so a
 * delete and an insert issued by two separate calls are not caught.
 */
class TransactionalWriteGuardTest {

  private static final Path SRC = Path.of("src/main/java/org/jdkxx/trader/storage");

  /**
   * Signature and body of a public method (the body ends at the closing brace indented by 2, which
   * matches this module's code style).
   */
  private static final Pattern METHOD =
      Pattern.compile(
          "(?m)^(?<anno>(?:  @[\\w.]+(?:\\([^)]*\\))?\\s*\\n)*)  public [^\\n(]+\\((?:[^)]|\\n)*?\\)\\s*\\{\\n(?<body>.*?)^  \\}",
          Pattern.DOTALL);

  @Test
  void 先删后插的写入都带了事务() throws IOException {
    List<String> offenders = new ArrayList<>();
    int scanned = 0;
    try (Stream<Path> files = Files.walk(SRC)) {
      for (Path f :
          files
              .filter(
                  p ->
                      p.toString().endsWith("Repository.java")
                          || p.toString().endsWith("Store.java"))
              .toList()) {
        String src = Files.readString(f);
        Matcher m = METHOD.matcher(src);
        while (m.find()) {
          scanned++;
          String body = m.group("body");
          boolean deletes = body.contains("DELETE FROM");
          boolean inserts = body.contains("INSERT INTO") || body.contains("batchUpdate");
          if (deletes && inserts && !m.group("anno").contains("Transactional")) {
            offenders.add(f.getFileName() + " 里有一个先删后插的方法没带 @Transactional");
          }
        }
      }
    }
    assertThat(scanned).as("一个方法都没扫到，说明扫描本身失效了（代码风格变了？）").isGreaterThan(30);
    assertThat(offenders).as("先 DELETE 再 INSERT 而没有事务：中途失败会留下半条记录，而且多半没有任何检查看得见").isEmpty();
  }

  @Test
  void 扫描路径有效() {
    assertThat(Files.isDirectory(SRC)).as("源码目录 %s 不在，测试需要跟着改", SRC).isTrue();
  }
}
