package org.jdkxx.trader.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the real {@code scripts/check-secrets.sh} against probe files in a throw-away git repository
 * and asserts exactly which lines it reports.
 *
 * <p>The scan has failed silently three times (2.0.2, 3.1.2 and the 2026-10-31 review: JSON {@code
 * "password": "x"}, compact Postman key/value pairs, values starting with a symbol, the {@code <env
 * name=.. value=..>} form of client-id and gateway ports were all missed). Each time the gap was
 * found with hand-made probe files that were thrown away afterwards, so nothing kept the next
 * pattern edit honest. This test is those probes made permanent: positives must be reported,
 * placeholders must not, in both working-tree and {@code --staged} mode.
 *
 * <p>Every positive probe line below carries {@code secrets-ok} in a trailing comment so that the
 * real repository scan skips this source file; the probe text written to the temporary repository
 * does not contain the marker.
 */
class CheckSecretsScriptTest {

  private static final Path ROOT = Path.of("..");

  /** Each line must be reported by at least one pattern. */
  private static final List<String> POSITIVES =
      List.of(
          "password: Hunter2Xyz99", // secrets-ok
          "# token: abcdef123456", // secrets-ok: comment lines are not exempt
          "TRADER_DB_PASSWORD=Hunter2Xyz99", // secrets-ok: uppercase needs grep -i
          "{\"password\": \"Hunter2Xyz99\"}", // secrets-ok: JSON, quote after the keyword
          "{\"token\": \"abcDEF123456\"}", // secrets-ok
          "{\"key\":\"db_password\",\"value\":\"Hunter2Xyz99\"}", // secrets-ok: compact Postman
          "{\"key\": \"OPENAI_API_KEY\", \"value\": \"abcDEF123456\"}", // secrets-ok
          "password: !Xyz12345abc", // secrets-ok: value starts with a symbol
          "password: Ab#cd123xyz", // secrets-ok: # inside the value
          "password: ab$cdefgh99", // secrets-ok: $ inside the value
          "TRADER_DB_PASSWORD='{Xy12abcdef'", // secrets-ok: a single { is not a template
          "api-key: !Bang12345", // secrets-ok
          "<env name=\"TRADER_DB_PASSWORD\" value=\"Hunter2Xyz99\" />", // secrets-ok
          "client-id: 17", // secrets-ok
          "<env name=\"TRADER_IBKR_CLIENT_ID\" value=\"17\" />", // secrets-ok
          "export TRADER_IBKR_PORT=4001", // secrets-ok
          "<env name=\"TRADER_FUTU_PORT\" value=\"11111\" />", // secrets-ok
          "trader.opend.port: 11111", // secrets-ok
          "vps_port=22", // secrets-ok: replica.env format
          "shanghai_port=2022", // secrets-ok
          "api_port=11111", // secrets-ok: FutuOpenD.xml settings written as key=value
          "telnet_port=22222", // secrets-ok
          "ssh -L 5432:127.0.0.1:5432 u@203.0.113.7", // secrets-ok: per-address IPv4
          "account: U1234567"); // secrets-ok

  /** Placeholders, empty values and code that merely mentions a keyword: none may be reported. */
  private static final List<String> NEGATIVES =
      List.of(
          "password: ${TRADER_DB_PASSWORD}",
          "password: ${TRADER_DB_PASSWORD:}",
          "password: ${X:-defaultval}",
          "api-key: ${OPENAI_API_KEY:+set}",
          "case \"${MVNW_PASSWORD:+has-password}\" in",
          "token: {{token}}",
          "{\"key\": \"token\", \"value\": \"{{token}}\"}",
          "{\"key\": \"password\", \"value\": \"\"}",
          "{\"key\":\"password\",\"value\":\"\"}",
          "password: <your-password>",
          "password: ********",
          "password: ...",
          "password: `backtick`",
          "secret: [one, two, three]",
          "secret: short",
          "\"token\": {",
          "if (token == null) return;",
          "token = (String) req.getHeader(\"x\");",
          "int tokens = 0;",
          "tokens = countTokens(text);",
          "long inputTokens = usage.inputTokens();",
          "api-key:",
          "export TRADER_DB_PASSWORD=",
          "export TRADER_IBKR_PORT=",
          "export TRADER_IBKR_CLIENT_ID=",
          "<env name=\"TRADER_IBKR_CLIENT_ID\" value=\"\" />",
          "<env name=\"TRADER_DB_PASSWORD\" value=\"$TRADER_DB_PASSWORD$\" />",
          "vps_port=<port>",
          "ssh_port() { local v=\"${1}_port\"; echo \"${!v:-22}\"; }",
          "  port: 8083",
          "url: jdbc:postgresql://${TRADER_DB_HOST:127.0.0.1}:${TRADER_DB_PORT:5432}/db",
          "curl http://127.0.0.1:8093/actuator/health",
          "password: Hunter2Xyz99 # secrets-ok");

  /** Editor swap and backup copies: rejected by name even when force-added past .gitignore. */
  private static final List<String> BACKUPS =
      List.of(
          "deploy/config/.trader.env.swp", "deploy/config/trader.env~", "config/secrets.yml.bak");

  /** {@code " [label] path:line:content"} for content hits, {@code " [label] path"} for names. */
  private static final Pattern HIT = Pattern.compile("^  \\[[^\\]]+\\] (\\S+?)(?::(\\d+):.*)?$");

  @TempDir Path repo;

  @Test
  void 只报正例与备份文件_两种模式一致() throws Exception {
    Path script = ROOT.resolve("scripts/check-secrets.sh");
    Assumptions.assumeTrue(Files.isDirectory(ROOT.resolve("scripts")), "脚本目录不在（模块单独构建）");
    Assumptions.assumeTrue(
        works("bash", "--version") && works("git", "--version"), "没有 bash 或 git");
    assertThat(script).as("scripts 目录在而脚本不在：改名了？").exists();

    Files.createDirectories(repo.resolve("scripts"));
    Files.copy(script, repo.resolve("scripts/check-secrets.sh"));
    Files.copy(ROOT.resolve(".gitignore"), repo.resolve(".gitignore"));
    Files.write(repo.resolve("positives.txt"), POSITIVES, StandardCharsets.UTF_8);
    Files.write(repo.resolve("negatives.txt"), NEGATIVES, StandardCharsets.UTF_8);
    for (String b : BACKUPS) {
      Path p = repo.resolve(b);
      Files.createDirectories(p.getParent());
      // The swap file is binary (NUL bytes) like a real vim swap file, so grep -I skips its content
      // and only the file-name rule can catch it.
      byte[] body =
          b.endsWith(".swp")
              ? "b0VIM 9.1\0\0\0export TRADER_IBKR_ENABLED=false\n".getBytes(StandardCharsets.UTF_8)
              : "export TRADER_IBKR_ENABLED=false\n".getBytes(StandardCharsets.UTF_8);
      Files.write(p, body);
    }

    assertThat(run("git", "init", "-q").exit).isZero();
    for (String b : BACKUPS) {
      assertThat(run("git", "check-ignore", "-q", b).exit).as(".gitignore 应忽略 " + b).isZero();
    }
    for (String other : List.of("a.swo", "x.orig", "notes~", "y.bak")) {
      assertThat(run("git", "check-ignore", "-q", other).exit)
          .as(".gitignore 应忽略 " + other)
          .isZero();
    }
    assertThat(run("git", "add", "-A").exit).isZero();
    List<String> forced = new ArrayList<>(List.of("git", "add", "-f", "--"));
    forced.addAll(BACKUPS);
    assertThat(run(forced.toArray(String[]::new)).exit).isZero();

    Set<String> expected = new TreeSet<>(BACKUPS);
    for (int i = 0; i < POSITIVES.size(); i++) {
      expected.add("positives.txt:" + (i + 1));
    }

    for (String mode : List.of("", "--staged")) {
      Result r =
          mode.isEmpty()
              ? run("bash", "scripts/check-secrets.sh")
              : run("bash", "scripts/check-secrets.sh", mode);
      assertThat(r.exit).as("退出码（%s）：%s", mode, r.output).isEqualTo(1);
      assertThat(reported(r.output)).as("报出的行（%s）：%s", mode, r.output).isEqualTo(expected);
    }
  }

  private static Set<String> reported(String output) {
    Set<String> hits = new TreeSet<>();
    for (String line : output.split("\n")) {
      Matcher m = HIT.matcher(line);
      if (m.matches()) {
        hits.add(m.group(2) == null ? m.group(1) : m.group(1) + ":" + m.group(2));
      }
    }
    return hits;
  }

  private record Result(int exit, String output) {}

  private Result run(String... command) throws IOException, InterruptedException {
    ProcessBuilder pb =
        new ProcessBuilder(command).directory(repo.toFile()).redirectErrorStream(true);
    // Keep the developer's global git config (core.excludesFile, hooks) out of the probe
    // repository.
    pb.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
    pb.environment().put("GIT_CONFIG_NOSYSTEM", "1");
    Process p = pb.start();
    byte[] out = p.getInputStream().readAllBytes();
    assertThat(p.waitFor(60, TimeUnit.SECONDS)).as("命令超时：" + String.join(" ", command)).isTrue();
    return new Result(p.exitValue(), new String(out, StandardCharsets.UTF_8));
  }

  private static boolean works(String... command) {
    try {
      Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
      p.getInputStream().readAllBytes();
      return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
    } catch (IOException e) {
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
