package org.jdkxx.trader.app;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 骨架验收：jar 内默认配置（无数据库、两家券商未启用）下应用能启动，系统页与健康检查可用。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TraderApplicationTests {

    @Autowired
    MockMvc mvc;

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
    void 健康检查为UP() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
