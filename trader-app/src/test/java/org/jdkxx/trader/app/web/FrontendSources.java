package org.jdkxx.trader.app.web;

import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Reads frontend sources for backend guard tests.
 *
 * <p>Why not simply {@code Files.exists(page) || return}: that is how {@code PoolRoleCoverageTest}
 * was written, and the {@code pages/MarketDataPage.vue} it watched was moved into {@code
 * components/system/MarketDataTab.vue} in the 3.0.6 frontend redesign, so the test <b>took the
 * return every time and never ran a single assertion</b>: a permanent green light that guarded
 * nothing (found in the 2026-09-25 full-project review). A "skip when the file is gone" guard
 * silently stops working as soon as the file is renamed, with no sign at all.
 *
 * <p>So the two cases are separated: only when the whole {@code trader-web/src} tree is absent
 * (backend built on its own) is the test skipped; if the tree is there but the file is not, the
 * frontend moved and the test did not follow, which <b>fails right away</b>.
 */
final class FrontendSources {

  private static final Path ROOT = Path.of("../trader-web/src");

  private FrontendSources() {}

  /**
   * Reads one frontend source file; returns empty only when the whole frontend source tree does not
   * exist.
   */
  static Optional<String> read(String relativeToSrc) throws IOException {
    if (!Files.isDirectory(ROOT)) {
      return Optional.empty();
    }
    Path file = ROOT.resolve(relativeToSrc);
    if (!Files.exists(file)) {
      fail("前端源码树在，但 %s 不在——多半是前端挪了位置而这个守护测试没跟着改。" + "别把它改回\"文件不存在就跳过\"：那样测试会永久绿灯却什么都不守", file);
    }
    return Optional.of(Files.readString(file));
  }
}
