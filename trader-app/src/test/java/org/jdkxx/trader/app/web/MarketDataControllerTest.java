package org.jdkxx.trader.app.web;

import org.jdkxx.trader.core.marketdata.MarketDataFacade;
import org.jdkxx.trader.core.marketdata.PoolService;
import org.jdkxx.trader.core.marketdata.audit.BarAuditService;
import org.jdkxx.trader.core.marketdata.bars.BarQueryService;
import org.jdkxx.trader.core.marketdata.jobs.JobService;
import org.jdkxx.trader.domain.PoolRole;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class MarketDataControllerTest {

    private final PoolService pool = mock(PoolService.class);
    private final MarketDataController controller = new MarketDataController(mock(MarketDataFacade.class), pool,
            mock(BarQueryService.class), mock(JobService.class), mock(BarAuditService.class));

    @Test
    void 手工加HOLDING被拒_由盈透持仓自动维护() {
        assertThatThrownBy(() -> controller.addToPool("AAPL", "HOLDING", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("/api/account/holdings/sync");
        verifyNoInteractions(pool);
    }

    @Test
    void POOL与BENCHMARK照常加入() {
        controller.addToPool("AAPL", "POOL", null);
        controller.addToPool("QQQ", "BENCHMARK", null);

        verify(pool).add("AAPL", PoolRole.POOL, null);
        verify(pool).add("QQQ", PoolRole.BENCHMARK, null);
    }
}
