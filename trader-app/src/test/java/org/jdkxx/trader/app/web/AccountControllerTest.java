package org.jdkxx.trader.app.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.jdkxx.trader.core.account.AccountAuditService;
import org.jdkxx.trader.core.account.AccountFacade;
import org.jdkxx.trader.core.account.HoldingReviewService;
import org.jdkxx.trader.core.account.HoldingSyncService;
import org.jdkxx.trader.core.account.LiveAccountService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Handler-level backstop for the side-effecting {@code GET /api/account/live}: even without
 * {@link LocalRequestGuardFilter} in the chain, a request lacking the client header must not reach
 * {@link LiveAccountService#view()}, whose first call starts standing IBKR subscriptions.
 */
class AccountControllerTest {

  private final LiveAccountService live = mock(LiveAccountService.class);
  private final MockMvc mvc =
      MockMvcBuilders.standaloneSetup(
              new AccountController(
                  mock(AccountFacade.class),
                  mock(HoldingSyncService.class),
                  mock(AccountAuditService.class),
                  live,
                  mock(HoldingReviewService.class)))
          .build();

  @Test
  void 实时账户没有自定义头_不触达订阅() throws Exception {
    mvc.perform(get("/api/account/live")).andExpect(status().isBadRequest());
    verify(live, never()).view();
  }

  @Test
  void 实时账户带自定义头_正常读取() throws Exception {
    mvc.perform(get("/api/account/live").header(LocalRequestGuardFilter.CLIENT_HEADER, "web"))
        .andExpect(status().isOk());
    verify(live).view();
  }
}
