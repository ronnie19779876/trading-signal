package org.jdkxx.trader.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Loopback does not stop a browser on the same machine: before 2.0.2 any web page could POST to
 * 127.0.0.1 (which is production while a tunnel is open), and DNS rebinding could even read the
 * positions.
 */
class LocalRequestGuardFilterTest {

  @RestController
  static class PingController {
    @GetMapping("/api/ping")
    String read() {
      return "ok";
    }

    /**
     * A read with side effects: the first read starts a resident subscription at IBKR, and every
     * read refreshes the idle-unsubscribe timer.
     */
    @GetMapping("/api/account/live")
    String liveAccount() {
      return "ok";
    }

    @PostMapping("/actuator/shutdown")
    String write() {
      return "ok";
    }
  }

  private final MockMvc mvc =
      MockMvcBuilders.standaloneSetup(new PingController())
          .addFilters(new LocalRequestGuardFilter())
          .build();

  @Test
  void 回环主机名放行_端口不限() throws Exception {
    for (String host :
        List.of("localhost:8083", "127.0.0.1:8093", "[::1]:18093", "LOCALHOST", "127.0.0.1")) {
      mvc.perform(get("/api/ping").header("Host", host)).andExpect(status().isOk());
    }
  }

  @Test
  void 非回环Host拒绝_防DNS重绑定() throws Exception {
    for (String host :
        List.of(
            "evil.example:8093",
            "127.0.0.1.evil.example",
            "192.0.2.1:8093")) { // secrets-ok: RFC 5737 documentation address
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

    mvc.perform(
            post("/actuator/shutdown")
                .header("Host", "127.0.0.1:8093")
                .header(LocalRequestGuardFilter.CLIENT_HEADER, "script"))
        .andExpect(status().isOk());
  }

  /**
   * "GET does not change state" is not universally true: {@code GET /api/account/live} really
   * starts a resident subscription at IBKR, a side effect a cross-site GET can trigger (found in
   * the 2026-09-25 full-project review).
   */
  @Test
  void 有副作用的读也要自定义头() throws Exception {
    mvc.perform(get("/api/account/live").header("Host", "127.0.0.1:8093"))
        .andExpect(status().isForbidden())
        .andExpect(content().string(containsString(LocalRequestGuardFilter.CLIENT_HEADER)));

    mvc.perform(
            get("/api/account/live")
                .header("Host", "127.0.0.1:8093")
                .header(LocalRequestGuardFilter.CLIENT_HEADER, "web"))
        .andExpect(status().isOk());
  }

  @Test
  void 读操作不要求自定义头() throws Exception {
    // the browser's EventSource (real-time quote stream) cannot send custom headers
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
