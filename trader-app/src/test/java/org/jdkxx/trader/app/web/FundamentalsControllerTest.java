package org.jdkxx.trader.app.web;

import org.jdkxx.trader.core.marketdata.fundamentals.FundamentalsAuditService;
import org.jdkxx.trader.core.marketdata.fundamentals.FundamentalsFacade;
import org.jdkxx.trader.core.marketdata.fundamentals.FundamentalsQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全市场估值 /api/fundamentals/valuations 与单只概览 /api/fundamentals/{symbol} 共用前缀：
 * 字面路径必须优先，否则 "valuations" 会被当成代码走进概览（404 "找不到标的"）。
 */
class FundamentalsControllerTest {

    private final FundamentalsQueryService query = mock(FundamentalsQueryService.class);
    private final MockMvc mvc;

    FundamentalsControllerTest() {
        FundamentalsFacade facade = mock(FundamentalsFacade.class);
        when(facade.query()).thenReturn(query);
        mvc = MockMvcBuilders.standaloneSetup(new FundamentalsController(facade, mock(FundamentalsAuditService.class))).build();
    }

    @Test
    void 全市场估值走字面路径_不被当成代码() throws Exception {
        when(query.valuationsOn(null)).thenReturn(new FundamentalsQueryService.DayValuations(LocalDate.of(2026, 9, 18), List.of()));

        mvc.perform(get("/api/fundamentals/valuations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows").isArray());

        verify(query).valuationsOn(null);
        verify(query, never()).overview(anyString());
    }

    @Test
    void 带日期查某一天() throws Exception {
        LocalDate d = LocalDate.of(2026, 9, 17);
        when(query.valuationsOn(d)).thenReturn(new FundamentalsQueryService.DayValuations(d, List.of()));

        mvc.perform(get("/api/fundamentals/valuations").param("date", "2026-09-17")).andExpect(status().isOk());

        verify(query).valuationsOn(d);
    }

    @Test
    void 单只概览照常() throws Exception {
        mvc.perform(get("/api/fundamentals/NVDA")).andExpect(status().isOk());

        verify(query).overview("NVDA");
    }
}
