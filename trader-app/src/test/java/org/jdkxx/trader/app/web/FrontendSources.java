package org.jdkxx.trader.app.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.fail;

/**
 * 读前端源码给后端守护测试用。
 *
 * <p>为什么不直接 {@code Files.exists(page) || return}：{@code PoolRoleCoverageTest} 就是这么写的，
 * 它盯的 {@code pages/MarketDataPage.vue} 在 3.0.6 前端改版里被挪进了 {@code components/system/MarketDataTab.vue}，
 * 于是测试**每次都走 return、一条断言都没跑过**，永久绿灯却什么都没守住
 * （2026-09-25 全项目审查发现）。这类"文件没了就跳过"的守护，文件一旦改名就自动失效，
 * 而且失效时毫无迹象。
 *
 * <p>这里把两种情况分开：整棵 {@code trader-web/src} 都不在（单独构建后端）才跳过；
 * 树在、只是那个文件不在，说明前端挪过位置而测试没跟上——<b>直接判失败</b>。
 */
final class FrontendSources {

    private static final Path ROOT = Path.of("../trader-web/src");

    private FrontendSources() {
    }

    /** 读一个前端源文件；只有整棵前端源码树都不存在时才返回空。 */
    static Optional<String> read(String relativeToSrc) throws IOException {
        if (!Files.isDirectory(ROOT)) {
            return Optional.empty();
        }
        Path file = ROOT.resolve(relativeToSrc);
        if (!Files.exists(file)) {
            fail("前端源码树在，但 %s 不在——多半是前端挪了位置而这个守护测试没跟着改。"
                    + "别把它改回\"文件不存在就跳过\"：那样测试会永久绿灯却什么都不守", file);
        }
        return Optional.of(Files.readString(file));
    }
}
