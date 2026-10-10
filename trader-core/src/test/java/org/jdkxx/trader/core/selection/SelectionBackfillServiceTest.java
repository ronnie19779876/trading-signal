package org.jdkxx.trader.core.selection;

import org.jdkxx.trader.core.marketdata.jobs.JobContext;
import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.InstrumentInfo;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.SecurityType;
import org.jdkxx.trader.domain.selection.SplitAdjustedBar;
import org.jdkxx.trader.domain.selection.ValuationMetric;
import org.jdkxx.trader.domain.selection.ValuationRange;
import org.jdkxx.trader.gateway.NotConnectedException;
import org.jdkxx.trader.gateway.PriceHistoryGateway;
import org.jdkxx.trader.gateway.ReferenceDataGateway;
import org.jdkxx.trader.gateway.SelectionDataGateway;
import org.jdkxx.trader.storage.marketdata.DailyBarRepository;
import org.jdkxx.trader.storage.marketdata.FinancialRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.jdkxx.trader.storage.marketdata.RehabFactorRepository;
import org.jdkxx.trader.storage.selection.SelectionBackfillStateRepository;
import org.jdkxx.trader.storage.selection.SelectionEarningsRepository;
import org.jdkxx.trader.storage.selection.SelectionPriceBarRepository;
import org.jdkxx.trader.storage.selection.SelectionPriceSeamRepository;
import org.jdkxx.trader.storage.selection.SelectionValuationRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SelectionBackfillServiceTest {

    private static final SelectionProperties PROPS = new SelectionProperties(LocalDate.of(2016, 1, 1), 4, 50,
            ValuationRange.YEAR10, 3, 0.001, 0.95);

    private final SelectionTargets targets = mock(SelectionTargets.class);
    private final SelectionBackfillStateRepository states = mock(SelectionBackfillStateRepository.class);
    private final PriceHistoryGateway prices = mock(PriceHistoryGateway.class);
    private final ReferenceDataGateway contracts = mock(ReferenceDataGateway.class);
    private final SelectionDataGateway fundamentals = mock(SelectionDataGateway.class);
    private final InstrumentRepository instruments = mock(InstrumentRepository.class);
    private final DailyBarRepository dailyBars = mock(DailyBarRepository.class);
    private final RehabFactorRepository rehabs = mock(RehabFactorRepository.class);
    private final FinancialRepository financials = mock(FinancialRepository.class);
    private final SelectionPriceBarRepository priceBars = mock(SelectionPriceBarRepository.class);
    private final SelectionPriceSeamRepository seams = mock(SelectionPriceSeamRepository.class);
    private final SelectionValuationRepository valuations = mock(SelectionValuationRepository.class);
    private final SelectionEarningsRepository earnings = mock(SelectionEarningsRepository.class);

    private final List<String> partials = new ArrayList<>();
    private final JobContext ctx = new JobContext() {
        @Override
        public long id() {
            return 1;
        }

        @Override
        public void progress(String text) {
        }

        @Override
        public void partial(String reason) {
            partials.add(reason);
        }

        @Override
        public boolean cancelled() {
            return false;
        }
    };

    private final SelectionBackfillService service = new SelectionBackfillService(PROPS, targets, states, prices, contracts,
            fundamentals, instruments, dailyBars, rehabs, financials, priceBars, seams, valuations, earnings,
            Clock.fixed(Instant.parse("2026-10-10T16:00:00Z"), ZoneOffset.UTC), ZoneId.of("America/New_York"));

    private static InstrumentRow row(long id, String symbol) {
        return new InstrumentRow(id, Market.US, symbol, symbol, null, SecurityType.STOCK, 1, null, false, "NYSE", null, "RESOLVED");
    }

    private static DailyBar futu(String symbol, LocalDate d, double close) {
        BigDecimal c = BigDecimal.valueOf(close);
        return new DailyBar(Instrument.us(symbol), d, c, c, c, c, null, 1, null, null, null, null, false);
    }

    private static InstrumentInfo info(String conId, String currency) {
        return new InstrumentInfo(Instrument.us("X"), Broker.IBKR, conId, "X CORP", "NYSE", currency, null, null, null, null);
    }

    /** 回归：没取过的标的状态为 null，Set.of(...).contains(null) 会抛 NPE——整批作业在第一只就崩。 */
    @Test
    void 已完成的跳过_没取过的照常取() throws Exception {
        when(targets.only(any())).thenReturn(List.of(row(1, "AAA"), row(2, "BBB"), row(3, "CCC")));
        when(states.statuses("EARNINGS")).thenReturn(Map.of(1L, "OK", 2L, "FAILED"));
        when(fundamentals.earningsHistory(any())).thenReturn(CompletableFuture.completedFuture(List.of()));

        String summary = service.run(BackfillPart.EARNINGS, List.of(), false, ctx);

        verify(states, never()).save(eq(1L), anyString(), anyString(), any());
        verify(states).save(2L, "EARNINGS", "EMPTY", "券商没有公告日");
        verify(states).save(3L, "EARNINGS", "EMPTY", "券商没有公告日");
        assertThat(summary).contains("已完成跳过 1");
    }

    @Test
    void force时已完成的也重取() throws Exception {
        when(targets.only(any())).thenReturn(List.of(row(1, "AAA")));
        when(fundamentals.earningsHistory(any())).thenReturn(CompletableFuture.completedFuture(List.of()));

        service.run(BackfillPart.EARNINGS, List.of(), true, ctx);

        verify(states, never()).statuses(anyString());
        verify(states).save(1L, "EARNINGS", "EMPTY", "券商没有公告日");
    }

    @Test
    void 富途日K已够深的不取盈透() throws Exception {
        when(targets.only(any())).thenReturn(List.of(row(1, "AAPL")));
        when(dailyBars.coverage(1L)).thenReturn(Optional.of(new DailyBarRepository.InstrumentCoverage(1, 5000,
                LocalDate.of(2006, 1, 3), LocalDate.of(2026, 10, 9))));

        service.run(BackfillPart.PRICES, List.of(), false, ctx);

        verify(states).save(eq(1L), eq("PRICES"), eq("SKIPPED"), anyString());
        verify(prices, never()).splitAdjustedDailyBars(anyLong(), any(), any());
    }

    @Test
    void 合约多个候选不猜_记失败_后面的照常() throws Exception {
        when(targets.only(any())).thenReturn(List.of(row(1, "AAA"), row(2, "BBB")));
        when(dailyBars.coverage(anyLong())).thenReturn(Optional.of(new DailyBarRepository.InstrumentCoverage(1, 1000,
                LocalDate.of(2022, 9, 8), LocalDate.of(2026, 10, 9))));
        when(instruments.ibkrConId(anyLong())).thenReturn(Optional.empty());
        when(contracts.lookup(Instrument.us("AAA"))).thenReturn(CompletableFuture.completedFuture(
                List.of(info("1", "USD"), info("2", "USD"))));
        when(contracts.lookup(Instrument.us("BBB"))).thenReturn(CompletableFuture.completedFuture(List.of()));

        service.run(BackfillPart.PRICES, List.of(), false, ctx);

        verify(states).save(eq(1L), eq("PRICES"), eq("FAILED"), org.mockito.ArgumentMatchers.contains("2 个候选"));
        verify(states).save(eq(2L), eq("PRICES"), eq("FAILED"), org.mockito.ArgumentMatchers.contains("查无此标的"));
        verify(instruments, never()).bindIbkrConId(anyLong(), anyLong());
        assertThat(partials).anyMatch(p -> p.contains("2 只失败"));
    }

    @Test
    void 价格回补绑定合约_取到富途起点后四个月_落库并核对接缝() throws Exception {
        LocalDate futuFrom = LocalDate.of(2022, 9, 8);
        when(targets.only(any())).thenReturn(List.of(row(7, "KO")));
        when(dailyBars.coverage(7L)).thenReturn(Optional.of(new DailyBarRepository.InstrumentCoverage(7, 1000, futuFrom,
                LocalDate.of(2026, 10, 9))));
        when(instruments.ibkrConId(7L)).thenReturn(Optional.empty());
        when(contracts.lookup(Instrument.us("KO"))).thenReturn(CompletableFuture.completedFuture(List.of(info("8894", "USD"))));
        when(instruments.bindIbkrConId(7L, 8894L)).thenReturn(true);
        List<SplitAdjustedBar> ib = new ArrayList<>();
        List<DailyBar> fu = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            LocalDate d = futuFrom.plusDays(i);
            BigDecimal c = BigDecimal.valueOf(50 + i);
            ib.add(new SplitAdjustedBar(d, c, c, c, c, 0));
            fu.add(futu("KO", d, 50 + i));
        }
        ib.addFirst(new SplitAdjustedBar(LocalDate.of(2016, 1, 4), BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, 0));
        when(prices.splitAdjustedDailyBars(8894L, LocalDate.of(2016, 1, 1), LocalDate.of(2023, 1, 8)))
                .thenReturn(CompletableFuture.completedFuture(ib));
        when(dailyBars.find(any(), eq(7L), eq(futuFrom), eq(LocalDate.of(2023, 1, 8)))).thenReturn(fu);
        when(rehabs.find(any(), eq(7L))).thenReturn(List.of());

        service.run(BackfillPart.PRICES, List.of(), false, ctx);

        verify(priceBars).replaceAll(7L, ib, LocalDate.of(2026, 10, 10));
        verify(seams).save(eq(7L), eq(8894L), eq(LocalDate.of(2016, 1, 4)), eq(futuFrom.plusDays(5)), eq(futuFrom),
                eq(6), eq(5), eq(5), any(), eq(true), anyString());
        verify(states).save(eq(7L), eq("PRICES"), eq("OK"), org.mockito.ArgumentMatchers.contains("接缝通过"));
    }

    @Test
    void 网关未连接整批停下_不把后面的都记成失败() throws Exception {
        when(targets.only(any())).thenReturn(List.of(row(1, "AAA"), row(2, "BBB")));
        when(fundamentals.earningsHistory(any()))
                .thenReturn(CompletableFuture.failedFuture(new NotConnectedException(Broker.FUTU, "行情通道未连接")));

        service.run(BackfillPart.EARNINGS, List.of(), false, ctx);

        verify(states, never()).save(anyLong(), anyString(), anyString(), any());
        assertThat(partials).anyMatch(p -> p.contains("网关未连接") && p.contains("AAA"));
    }

    @Test
    void 三种估值都回空记EMPTY() throws Exception {
        when(targets.only(any())).thenReturn(List.of(row(1, "AAA")));
        when(fundamentals.valuationHistory(any(), any(ValuationMetric.class), eq(ValuationRange.YEAR10)))
                .thenReturn(CompletableFuture.completedFuture(List.of()));

        service.run(BackfillPart.VALUATION, List.of(), false, ctx);

        verify(states).save(eq(1L), eq("VALUATION"), eq("EMPTY"), anyString());
    }

    @Test
    void 季报三类都取且写进共用财报表() throws Exception {
        when(targets.only(any())).thenReturn(List.of(row(1, "AAA")));
        when(fundamentals.quarterlyFinancials(any(), any(), anyInt())).thenReturn(CompletableFuture.completedFuture(List.of()));

        service.run(BackfillPart.FINANCIALS, List.of(), false, ctx);

        verify(fundamentals).quarterlyFinancials(Instrument.us("AAA"), org.jdkxx.trader.domain.FinancialStatement.INCOME, 50);
        verify(fundamentals).quarterlyFinancials(Instrument.us("AAA"), org.jdkxx.trader.domain.FinancialStatement.BALANCE_SHEET, 50);
        verify(fundamentals).quarterlyFinancials(Instrument.us("AAA"), org.jdkxx.trader.domain.FinancialStatement.CASH_FLOW, 50);
        verify(states).save(eq(1L), eq("FINANCIALS"), eq("EMPTY"), anyString());
    }
}
