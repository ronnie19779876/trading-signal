package org.jdkxx.trader.core.selection;

import org.jdkxx.trader.core.marketdata.jobs.JobContext;
import org.jdkxx.trader.gateway.SelectionDataGateway;
import org.jdkxx.trader.storage.marketdata.FinancialRepository;
import org.jdkxx.trader.storage.selection.SelectionEarningsRepository;
import org.jdkxx.trader.storage.selection.SelectionValuationRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SelectionDailyServiceTest {

    private final JobContext ctx = new JobContext() {
        public long id() { return 1; }
        public void progress(String text) { }
        public void partial(String reason) { }
        public boolean cancelled() { return false; }
    };

    /**
     * 回归（3.2.0 漏了）：周作业除了全量刷新公告日，还要对证券类型、估值历史、季报、盈透价格各跑一次「只补缺的」回补，
     * 成分股同步新进的标的才会补齐深度输入。
     */
    @Test
    void 周作业刷新公告日并只补缺的四项() {
        SelectionBackfillService backfill = mock(SelectionBackfillService.class);
        when(backfill.run(any(), any(), anyBoolean(), any())).thenReturn("ok");
        SelectionDailyService service = new SelectionDailyService(mock(SelectionTargets.class), mock(SelectionValuationRepository.class),
                mock(SelectionEarningsRepository.class), mock(FinancialRepository.class), mock(SelectionDataGateway.class),
                mock(SelectionScoringService.class), backfill);

        service.weekly(ctx);

        InOrder o = inOrder(backfill);
        o.verify(backfill).run(eq(BackfillPart.EARNINGS), eq(List.of()), eq(true), any());
        o.verify(backfill).run(eq(BackfillPart.TYPES), eq(List.of()), eq(false), any());
        o.verify(backfill).run(eq(BackfillPart.VALUATION), eq(List.of()), eq(false), any());
        o.verify(backfill).run(eq(BackfillPart.FINANCIALS), eq(List.of()), eq(false), any());
        o.verify(backfill).run(eq(BackfillPart.PRICES), eq(List.of()), eq(false), any());
    }
}
