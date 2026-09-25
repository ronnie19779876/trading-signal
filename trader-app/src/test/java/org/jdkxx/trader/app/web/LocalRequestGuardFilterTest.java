package org.jdkxx.trader.app.web;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 回环地址挡不住本机浏览器：2.0.2 前任意网页都能向 127.0.0.1 发 POST（开着隧道时就是生产），
 * DNS rebinding 还能读到持仓。
 */
class LocalRequestGuardFilterTest {

    @RestController
    static class PingController {
        @GetMapping("/api/ping")
        String read() {
            return "ok";
        }

        /** 有副作用的读：第一次读会向盈透发起常驻订阅，每次读还刷新闲置退订的计时。 */
        @GetMapping("/api/account/live")
        String liveAccount() {
            return "ok";
        }

        @PostMapping("/actuator/shutdown")
        String write() {
            return "ok";
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new PingController())
            .addFilters(new LocalRequestGuardFilter()).build();

    @Test
    void 回环主机名放行_端口不限() throws Exception {
        for (String host : List.of("localhost:8083", "127.0.0.1:8093", "[::1]:18093", "LOCALHOST", "127.0.0.1")) {
            mvc.perform(get("/api/ping").header("Host", host)).andExpect(status().isOk());
        }
    }

    @Test
    void 非回环Host拒绝_防DNS重绑定() throws Exception {
        for (String host : List.of("evil.example:8093", "127.0.0.1.evil.example", "192.0.2.1:8093")) {   // secrets-ok 192.0.2.x 是 RFC 5737 文档保留地址
            mvc.perform(get("/api/ping").header("Host", host))
                    .andExpect(status().isForbidden())
                    .andExpect(content().string(containsString("REQUEST_REJECTED")));
        }
    }

    @Test
    void 写操作缺自定义头拒绝_防跨站POST() throws Exception {
        mvc.perform(post("/actuator/shutdown").header("Host", "127.0.0.1:8093"))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString(LocalRequestGuardFilter.CLIENT_HEADER)));

        mvc.perform(post("/actuator/shutdown").header("Host", "127.0.0.1:8093").header(LocalRequestGuardFilter.CLIENT_HEADER, "script"))
                .andExpect(status().isOk());
    }

    /**
     * 「GET 不改状态」并非全都成立：{@code GET /api/account/live} 会真的向盈透发起常驻订阅，
     * 是一个可被跨站 GET 触发的副作用（2026-09-25 全项目审查发现）。
     */
    @Test
    void 有副作用的读也要自定义头() throws Exception {
        mvc.perform(get("/api/account/live").header("Host", "127.0.0.1:8093"))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString(LocalRequestGuardFilter.CLIENT_HEADER)));

        mvc.perform(get("/api/account/live").header("Host", "127.0.0.1:8093")
                        .header(LocalRequestGuardFilter.CLIENT_HEADER, "web"))
                .andExpect(status().isOk());
    }

    @Test
    void 读操作不要求自定义头() throws Exception {
        // 浏览器的 EventSource（实时报价流）发不了自定义头
        mvc.perform(get("/api/ping").header("Host", "localhost:5174")).andExpect(status().isOk());
    }

    @Test
    void Host主机名解析() {
        assertThat(LocalRequestGuardFilter.hostName("[::1]:8083")).isEqualTo("[::1]");
        assertThat(LocalRequestGuardFilter.hostName("LocalHost:1")).isEqualTo("localhost");
        assertThat(LocalRequestGuardFilter.hostName("127.0.0.1")).isEqualTo("127.0.0.1");
        assertThat(LocalRequestGuardFilter.hostName(" ")).isNull();
    }
}
