package org.jdkxx.trader.app.web;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 前端深链接回退：2.0.0 及以前直接打开 /account、/marketdata、/fundamentals 都是 404，从首页点进去才正常。
 */
class SpaForwardControllerTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new SpaForwardController()).build();

    @Test
    void 前端深链接转发给首页() throws Exception {
        for (String p : List.of("/account", "/marketdata", "/fundamentals")) {
            mvc.perform(get(p)).andExpect(forwardedUrl("/index.html"));
        }
    }

    @Test
    void 接口_actuator_错误页与静态文件不转发() throws Exception {
        for (String p : List.of("/api", "/actuator", "/error", "/api/pool", "/actuator/health",
                "/favicon.ico", "/index.html", "/assets/AccountPage-x.js")) {
            mvc.perform(get(p)).andExpect(status().isNotFound());
        }
    }

    @Test
    void 前端路由表里的路径都能深链接打开() throws Exception {
        Path router = Path.of("../trader-web/src/router/index.ts");
        if (!Files.exists(router)) {
            return;   // 只在完整仓库里检查；单独构建后端时跳过
        }
        Matcher m = Pattern.compile("path:\\s*'([^']+)'").matcher(Files.readString(router));
        List<String> paths = new ArrayList<>();
        while (m.find()) {
            paths.add(m.group(1));
        }
        assertThat(paths).as("没从 router/index.ts 里读到路由，测试需要跟着改").isNotEmpty();
        for (String p : paths) {
            if ("/".equals(p)) {
                continue;   // 首页由静态资源的欢迎页提供
            }
            assertThat(p).as("前端路由必须是单段、不含点的路径，否则深链接回退覆盖不到：%s", p).matches("/[^/.]+");
            mvc.perform(get(p)).andExpect(forwardedUrl("/index.html"));
        }
    }
}
