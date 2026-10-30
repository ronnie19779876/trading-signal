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
                mock(SelectionScoringService.class), backfill, mock(SelectionSimService.class));

        service.weekly(ctx);

        InOrder o = inOrder(backfill);
        o.verify(backfill).run(eq(BackfillPart.EARNINGS), eq(List.of()), eq(true), any());
        o.verify(backfill).run(eq(BackfillPart.TYPES), eq(List.of()), eq(false), any());
        o.verify(backfill).run(eq(BackfillPart.VALUATION), eq(List.of()), eq(false), any());
        o.verify(backfill).run(eq(BackfillPart.FINANCIALS), eq(List.of()), eq(false), any());
        o.verify(backfill).run(eq(BackfillPart.PRICES), eq(List.of()), eq(false), any());
    }

    /**
     * 3.4.6：公告超过 6 天、但该季报表还没到齐的（2026-10-22 生产 JPM 公告 8 天仍缺资产负债表与现金流）继续补取；
     * 到齐的、超过 45 天的不再取（后者由审计列出）。
     */
    @Test
    void 报表未到齐的公告后45天内继续补取() throws Exception {
        java.time.LocalDate d = java.time.LocalDate.of(2026, 10, 21);
        SelectionTargets targets = mock(SelectionTargets.class);
        org.jdkxx.trader.storage.marketdata.InstrumentRow abt = row(1, "ABT"), jpm = row(2, "JPM"), ko = row(3, "KO");
        when(targets.all()).thenReturn(List.of(abt, jpm, ko));
        SelectionEarningsRepository earnings = mock(SelectionEarningsRepository.class);
        when(earnings.instrumentsWithPubBetween(d.minusDays(SelectionDailyService.REFRESH_LOOKBACK_DAYS), d)).thenReturn(java.util.Set.of(1L));
        when(earnings.incompleteFinancials(d.minusDays(SelectionDailyService.COMPLETE_LOOKBACK_DAYS), d)).thenReturn(List.of(
                new SelectionEarningsRepository.IncompleteFinancials(1, "ABT", d, List.of("BALANCE_SHEET", "CASH_FLOW")),
                new SelectionEarningsRepository.IncompleteFinancials(2, "JPM", d.minusDays(8), List.of("CASH_FLOW"))));
        SelectionDataGateway gw = mock(SelectionDataGateway.class);
        when(gw.quarterlyFinancials(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(List.of()));
        when(gw.valuationHistory(any(), any(), any())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(List.of()));
        SelectionScoringService scoring = mock(SelectionScoringService.class);
        when(scoring.scoreDay(any(), any())).thenReturn("评分");
        SelectionSimService sim = mock(SelectionSimService.class);
        when(sim.update(any(), any())).thenReturn("模拟");
        SelectionDailyService service = new SelectionDailyService(targets, mock(SelectionValuationRepository.class), earnings,
                mock(FinancialRepository.class), gw, scoring, mock(SelectionBackfillService.class), sim);

        String summary = service.daily(d, ctx);

        org.mockito.Mockito.verify(gw, org.mockito.Mockito.times(3)).quarterlyFinancials(eq(abt.instrument()), any(), org.mockito.ArgumentMatchers.anyInt());
        org.mockito.Mockito.verify(gw, org.mockito.Mockito.times(3)).quarterlyFinancials(eq(jpm.instrument()), any(), org.mockito.ArgumentMatchers.anyInt());
        org.mockito.Mockito.verify(gw, org.mockito.Mockito.never()).quarterlyFinancials(eq(ko.instrument()), any(), org.mockito.ArgumentMatchers.anyInt());
        org.assertj.core.api.Assertions.assertThat(summary).contains("新公告补取 2/2（其中报表未到齐续取 1 只）");
    }

    /** 3.5.0：池与持仓的公告日每天刷新（持仓风险面板要的预定日期最多晚一天）；一只失败不影响其余，只记部分失败。 */
    @Test
    void 每日先刷新池与持仓的公告日() throws Exception {
        java.time.LocalDate d = java.time.LocalDate.of(2026, 10, 30);
        SelectionTargets targets = mock(SelectionTargets.class);
        org.jdkxx.trader.storage.marketdata.InstrumentRow goog = row(21, "GOOG"), msft = row(22, "MSFT");
        when(targets.all()).thenReturn(List.of(goog, msft));
        when(targets.poolAndHoldings()).thenReturn(List.of(goog, msft));
        SelectionBackfillService backfill = mock(SelectionBackfillService.class);
        when(backfill.earnings(goog)).thenThrow(new IllegalStateException("超时"));
        SelectionScoringService scoring = mock(SelectionScoringService.class);
        when(scoring.scoreDay(any(), any())).thenReturn("评分");
        SelectionSimService sim = mock(SelectionSimService.class);
        when(sim.update(any(), any())).thenReturn("模拟");
        List<String> partials = new java.util.ArrayList<>();
        JobContext recording = new JobContext() {
            public long id() { return 1; }
            public void progress(String text) { }
            public void partial(String reason) { partials.add(reason); }
            public boolean cancelled() { return false; }
        };
        SelectionDailyService service = new SelectionDailyService(targets, mock(SelectionValuationRepository.class),
                mock(SelectionEarningsRepository.class), mock(FinancialRepository.class), mock(SelectionDataGateway.class), scoring, backfill, sim);

        String summary = service.daily(d, recording);

        org.mockito.Mockito.verify(backfill).earnings(goog);
        org.mockito.Mockito.verify(backfill).earnings(msft);
        org.assertj.core.api.Assertions.assertThat(summary).startsWith("池与持仓公告日 1/2；");
        org.assertj.core.api.Assertions.assertThat(partials).contains("池与持仓公告日刷新失败 1 只");
    }

    private static org.jdkxx.trader.storage.marketdata.InstrumentRow row(long id, String symbol) {
        return new org.jdkxx.trader.storage.marketdata.InstrumentRow(id, org.jdkxx.trader.domain.Market.US, symbol, symbol, null,
                org.jdkxx.trader.domain.SecurityType.STOCK, 1, null, false, "NYSE", null, "RESOLVED");
    }
}
