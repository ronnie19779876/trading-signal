package org.jdkxx.trader.app.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.jdkxx.trader.domain.PoolRole;
import org.junit.jupiter.api.Test;

/**
 * The pool-member endpoint and the frontend role dropdown must cover every value of {@link
 * PoolRole}.
 *
 * <p>This test was added after the fact: when the {@code BENCHMARK} role was added, {@code GET
 * /api/pool} had POOL and HOLDING hard-coded and the frontend dropdown had only two options, so the
 * benchmark existed in the database and was collected and subscribed normally but was missing from
 * the API response. The data was right but invisible, which during troubleshooting easily looks
 * like "the benchmark was never added".
 */
class PoolRoleCoverageTest {

  /**
   * Since 3.4.3 {@code /api/pool} delegates to {@code MarketDataFacade.pool()}: it takes every pool
   * member without filtering by role, so structurally no role can be missed (it used to call
   * universe() once per PoolRole.values(), each a GROUP BY over the whole daily-bar table, which
   * was too slow). "Every role is returned" is guarded by {@code InstrumentViewCoverageTest}
   * iterating PoolRole.values(); this test only guards that the endpoint does not bypass it and
   * assemble roles one by one itself.
   */
  @Test
  void 池接口不按角色写死() throws Exception {
    String src =
        Files.readString(
            Path.of("src/main/java/org/jdkxx/trader/app/web/MarketDataController.java"));
    int start = src.indexOf("@GetMapping(\"/api/pool\")");
    assertThat(start).as("找不到 /api/pool 的定义，测试需要跟着改").isGreaterThan(0);
    String body = src.substring(start, src.indexOf('}', src.indexOf('{', start)) + 1);

    assertThat(body)
        .as("池接口要委托给 facade.pool()（取全部成员、不按角色过滤），别逐个角色写死——加新角色时必漏")
        .contains("facade.pool()")
        .doesNotContain("PoolRole.POOL")
        .doesNotContain("PoolRole.HOLDING")
        .doesNotContain("PoolRole.BENCHMARK");
  }

  /**
   * After the 3.0.6 redesign the role dropdown moved to {@code
   * components/system/MarketDataTab.vue}, while this test kept pointing at the deleted {@code
   * pages/MarketDataPage.vue} and, through "return when the file does not exist", skipped every
   * time: a permanent green light with not one assertion run (found in the 2026-09-25 full-project
   * review). It now uses {@link FrontendSources}: tree present but file missing fails right away.
   */
  @Test
  void 前端角色下拉覆盖全部取值() throws Exception {
    Optional<String> src = FrontendSources.read("components/system/MarketDataTab.vue");
    if (src.isEmpty()) {
      return; // the whole frontend source tree is absent: backend built on its own
    }
    for (PoolRole role : PoolRole.values()) {
      assertThat(src.get()).as("前端角色下拉缺少 %s", role).contains("value=\"" + role.name() + "\"");
    }
  }
}
