package org.jdkxx.trader.core.selection;

import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.SecurityType;
import org.jdkxx.trader.domain.selection.Metric;
import org.jdkxx.trader.domain.selection.SplitAdjustedBar;
import org.jdkxx.trader.domain.selection.StockInputs;
import org.jdkxx.trader.storage.marketdata.DailyBarRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.jdkxx.trader.storage.marketdata.RehabFactorRepository;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;
import org.jdkxx.trader.storage.selection.SelectionInputRepository;
import org.jdkxx.trader.storage.selection.SelectionSecurityRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SelectionDataLoaderTest {

    private static final InstrumentRow ROW = new InstrumentRow(1, Market.US, "AAA", "AAA", null, SecurityType.STOCK, 1, null,
            false, "NYSE", null, "RESOLVED");

    private static List<LocalDate> weekdays(LocalDate from, LocalDate to) {
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                out.add(d);
            }
        }
        return out;
    }

    /**
     * 回归（2026-10-12 实测 2018-03-01 有 467 只「没有价格」）：富途日 K 2022-09 才开始，盈透段 2016~2022-12。
     * 盈透段要在重叠日按比值拼接，加载富途日 K 必须取到盈透段结束——只取到判定日的话，2018 年的判定日拼不上、整段没价格。
     */
    @Test
    void 早年判定日也能用拼接上的盈透价格() {
        SelectionTargets targets = mock(SelectionTargets.class);
        when(targets.all()).thenReturn(List.of(ROW));
        SelectionSectors sectors = mock(SelectionSectors.class);
        when(sectors.of(any())).thenReturn(Map.of(1L, "IT"));
        SelectionSecurityRepository securities = mock(SelectionSecurityRepository.class);
        when(securities.types()).thenReturn(Map.of(1L, "COMMON"));
        SelectionInputRepository inputs = mock(SelectionInputRepository.class);
        when(inputs.quarterlyFacts(any())).thenReturn(List.of());
        when(inputs.pubDates(any())).thenReturn(Map.of());
        List<SplitAdjustedBar> ib = new ArrayList<>();
        for (LocalDate d : weekdays(LocalDate.of(2016, 1, 4), LocalDate.of(2022, 12, 30))) {
            ib.add(new SplitAdjustedBar(d, null, null, null, BigDecimal.valueOf(50 + d.toEpochDay() % 7), 0));
        }
        when(inputs.seamPassedIbkrBars(any())).thenReturn(Map.of(1L, ib));
        List<DailyBar> futu = new ArrayList<>();
        for (LocalDate d : weekdays(LocalDate.of(2022, 9, 8), LocalDate.of(2026, 9, 2))) {
            BigDecimal c = BigDecimal.valueOf(50 + d.toEpochDay() % 7);
            futu.add(new DailyBar(Instrument.us("AAA"), d, c, c, c, c, null, 1, null, null, null, null, false));
        }
        DailyBarRepository dailyBars = mock(DailyBarRepository.class);
        when(dailyBars.findMany(any(), any(), any())).thenAnswer(inv -> {
            LocalDate from = inv.getArgument(1);
            LocalDate to = inv.getArgument(2);
            return Map.of(1L, futu.stream().filter(b -> !b.tradeDate().isBefore(from) && !b.tradeDate().isAfter(to)).toList());
        });
        RehabFactorRepository rehabs = mock(RehabFactorRepository.class);
        when(rehabs.findMany(any())).thenReturn(Map.of());
        TradingDayRepository days = mock(TradingDayRepository.class);
        when(days.between(any(), any(), any())).thenReturn(List.of());

        LocalDate t = LocalDate.of(2018, 3, 1);
        SelectionDataset data = new SelectionDataLoader(targets, sectors, inputs, securities, dailyBars, rehabs, days).load(t, t);
        StockInputs s = data.inputsOn(t).getFirst();

        assertThat(s.metrics()).containsKeys(Metric.MOMENTUM_3M, Metric.MOMENTUM_6M, Metric.MOMENTUM_12M);
    }
}
