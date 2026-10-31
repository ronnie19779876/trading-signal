package org.jdkxx.trader.app.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.jdkxx.trader.core.marketdata.valuation.SotpService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Routing guard: the four paths under /api/valuation/sotp share a prefix, and taking the wrong one
 * does not error but <b>computes for a different symbol</b>. The trial calculation deliberately
 * uses the two segments calc/{symbol} so that POST /api/valuation/sotp/calc is not taken as
 * symbol=calc.
 */
class ValuationControllerTest {

  private static final String BODY =
      """
            {"name":"2030 基准","asOf":"2026-09-19","targetYear":2030,"discountRate":0.10,
             "targetShares":4000000000,"targetNetCash":0,
             "segments":[{"name":"主业","scopeNote":"只算主业","cases":{
               "BEAR":{"volume":1,"price":1,"netMargin":0.05,"pe":10},
               "BASE":{"volume":1,"price":1,"netMargin":0.07,"pe":15},
               "BULL":{"volume":1,"price":1,"netMargin":0.09,"pe":20}}}]}
            """;

  private final SotpService sotp = mock(SotpService.class);
  private final MockMvc mvc =
      MockMvcBuilders.standaloneSetup(new ValuationController(sotp)).build();

  @Test
  void 底座走inputs_不被当成方案列表() throws Exception {
    mvc.perform(get("/api/valuation/sotp/TSLA/inputs")).andExpect(status().isOk());

    verify(sotp).inputs("TSLA");
    verify(sotp, never()).models(anyString());
  }

  @Test
  void 方案列表() throws Exception {
    when(sotp.models("TSLA")).thenReturn(List.of());

    mvc.perform(get("/api/valuation/sotp/TSLA")).andExpect(status().isOk());

    verify(sotp).models("TSLA");
  }

  @Test
  void 试算走两段路径_symbol不会被当成calc() throws Exception {
    mvc.perform(post("/api/valuation/sotp/calc/TSLA").contentType("application/json").content(BODY))
        .andExpect(status().isOk());

    verify(sotp).calc(eq("TSLA"), any());
    verify(sotp, never()).save(anyString(), any()); // a trial calculation must never reach save
  }

  @Test
  void 保存走单段路径_不会误落到试算() throws Exception {
    mvc.perform(post("/api/valuation/sotp/TSLA").contentType("application/json").content(BODY))
        .andExpect(status().isOk());

    verify(sotp).save(eq("TSLA"), any());
    verify(sotp, never()).calc(anyString(), any());
  }

  @Test
  void 删除按id() throws Exception {
    mvc.perform(delete("/api/valuation/sotp/7")).andExpect(status().isOk());

    verify(sotp).delete(7L);
  }
}
