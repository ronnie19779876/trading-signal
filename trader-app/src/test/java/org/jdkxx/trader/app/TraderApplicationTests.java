package org.jdkxx.trader.app;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Skeleton acceptance: with the jar's default configuration (no database, both brokers disabled)
 * the application starts, and the system page, gateway endpoints and health check work.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TraderApplicationTests {

  @Autowired MockMvc mvc;

  @Test
  void 系统信息接口报告版本环境与两家券商() throws Exception {
    mvc.perform(get("/api/system/info"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.application").value("trading-signal"))
        .andExpect(jsonPath("$.environment").value("未声明"))
        .andExpect(jsonPath("$.database.enabled").value(false))
        .andExpect(jsonPath("$.gateways", hasSize(2)))
        .andExpect(jsonPath("$.gateways[0].broker").value("IBKR"))
        .andExpect(jsonPath("$.gateways[0].state").value("DISABLED"))
        .andExpect(jsonPath("$.gateways[1].broker").value("FUTU"))
        .andExpect(jsonPath("$.ai.configured").value(false));
  }

  @Test
  void 网关接口与错误映射() throws Exception {
    mvc.perform(get("/api/gateways"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(2)))
        .andExpect(jsonPath("$[0].enabled").value(false));
    mvc.perform(get("/api/gateways/ibkr"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("DISABLED"));
    mvc.perform(post("/api/gateways/ibkr/connect").header("X-Trader-Client", "test"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
    mvc.perform(get("/api/gateways/xyz")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/gateways/events"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(0)));
  }

  /**
   * The local-request guard is really wired into the application (not just a filter attached by
   * hand in a unit test).
   */
  @Test
  void 写请求缺自定义头被拒_非回环Host被拒() throws Exception {
    mvc.perform(post("/api/gateways/ibkr/connect"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("REQUEST_REJECTED"));
    mvc.perform(get("/api/system/info").header("Host", "evil.example:8093"))
        .andExpect(status().isForbidden());
  }

  /**
   * Checked against the real handler mappings: deep links forward to the index page, and actuator
   * and API paths are not captured by the fallback.
   */
  @Test
  void 前端深链接回退不抢接口与actuator() throws Exception {
    mvc.perform(get("/account"))
        .andExpect(status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl(
                "/index.html"));
    mvc.perform(get("/actuator"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$._links").exists());
    mvc.perform(get("/api/system/info"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.application").value("trading-signal"));
  }

  @Test
  void 健康检查为UP且网关指标存在() throws Exception {
    mvc.perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"))
        .andExpect(jsonPath("$.components.gateways.status").value("UP"));
  }
}
