package org.jdkxx.trader.storage;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「先删后插」的写入必须在事务里。
 *
 * <p>{@code FinancialRepository.upsertAll} 就漏过：整期替换是先 {@code DELETE FROM financial_item}
 * 再批量 INSERT，没有事务时批插入一失败，{@code financial_report} 留着期次行、{@code financial_item} 是空的，
 * 只剩一个空壳期次；而基本面审计的财报新鲜度只看 {@code period_end}，空壳反而让它认为「这期已经有了」，
 * 谁都发现不了（2026-09-25 全项目审查发现）。
 *
 * <p>按源码文本查：方法体里同时出现 DELETE 与 INSERT / batchUpdate，就必须带 {@code @Transactional}。
 * 反射查不到这个——方法体不在反射范围内。
 */
class TransactionalWriteGuardTest {

    private static final Path SRC = Path.of("src/main/java/org/jdkxx/trader/storage");

    /** public 方法的签名与方法体（按缩进 4 的闭合花括号断句，本模块的代码风格一致）。 */
    private static final Pattern METHOD = Pattern.compile(
            "(?m)^(?<anno>(?:    @[\\w.]+(?:\\([^)]*\\))?\\s*\\n)*)    public [^\\n(]+\\((?:[^)]|\\n)*?\\)\\s*\\{\\n(?<body>.*?)^    \\}",
            Pattern.DOTALL);

    @Test
    void 先删后插的写入都带了事务() throws IOException {
        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(SRC)) {
            for (Path f : files.filter(p -> p.toString().endsWith("Repository.java") || p.toString().endsWith("Store.java")).toList()) {
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
        assertThat(offenders)
                .as("先 DELETE 再 INSERT 而没有事务：中途失败会留下半条记录，而且多半没有任何检查看得见")
                .isEmpty();
    }

    @Test
    void 扫描路径有效() {
        assertThat(Files.isDirectory(SRC)).as("源码目录 %s 不在，测试需要跟着改", SRC).isTrue();
    }
}
