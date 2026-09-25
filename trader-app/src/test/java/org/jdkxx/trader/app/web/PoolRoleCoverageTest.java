package org.jdkxx.trader.app.web;

import org.jdkxx.trader.domain.PoolRole;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 池成员接口与前端角色下拉必须覆盖 {@link PoolRole} 的全部取值。
 *
 * <p>这个测试是补的：加 {@code BENCHMARK} 角色时，{@code GET /api/pool} 里写死枚举了
 * POOL 与 HOLDING 两种，前端下拉也只有两项，结果基准标的在库里存在、被正常采集与订阅，
 * 却不在接口返回里——数据没错，但看不见，排查时容易误判成"基准没加上"。
 */
class PoolRoleCoverageTest {

    @Test
    void 池接口不按角色写死() throws Exception {
        String src = Files.readString(Path.of("src/main/java/org/jdkxx/trader/app/web/MarketDataController.java"));
        int start = src.indexOf("@GetMapping(\"/api/pool\")");
        assertThat(start).as("找不到 /api/pool 的定义，测试需要跟着改").isGreaterThan(0);
        String body = src.substring(start, src.indexOf('}', src.indexOf('{', start)) + 1);

        assertThat(body)
                .as("池接口要遍历 PoolRole.values()，别逐个角色写死——加新角色时必漏")
                .contains("PoolRole.values()");
    }

    /**
     * 角色下拉在 3.0.6 改版后搬到了 {@code components/system/MarketDataTab.vue}，
     * 而这个测试一直指着已被删除的 {@code pages/MarketDataPage.vue}，靠"文件不存在就 return"
     * 每次都跳过——永久绿灯、一条断言没跑（2026-09-25 全项目审查发现）。
     * 现在走 {@link FrontendSources}：树在而文件不在直接判失败。
     */
    @Test
    void 前端角色下拉覆盖全部取值() throws Exception {
        Optional<String> src = FrontendSources.read("components/system/MarketDataTab.vue");
        if (src.isEmpty()) {
            return;   // 整棵前端源码树都不在：单独构建后端
        }
        for (PoolRole role : PoolRole.values()) {
            assertThat(src.get()).as("前端角色下拉缺少 %s", role).contains("value=\"" + role.name() + "\"");
        }
    }
}
