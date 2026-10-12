package org.jdkxx.trader.core.selection;

import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.SecurityType;
import org.jdkxx.trader.domain.selection.AdjustedPrices;
import org.jdkxx.trader.domain.selection.Exclusion;
import org.jdkxx.trader.domain.selection.Metric;
import org.jdkxx.trader.domain.selection.QuarterFacts;
import org.jdkxx.trader.domain.selection.StockInputs;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SelectionDatasetTest {

    private static final LocalDate T = LocalDate.of(2025, 6, 2);
    private static final InstrumentRow ROW = new InstrumentRow(1, Market.US, "AAA", "AAA", null, SecurityType.STOCK, 1, null,
            false, "NYSE", null, "RESOLVED");

    private static AdjustedPrices prices(double close, int days) {
        List<DailyBar> bars = new ArrayList<>();
        for (int i = days; i >= 0; i--) {
            BigDecimal c = BigDecimal.valueOf(close - i * 0.001);   // 判定日那天恰好是 close
            bars.add(new DailyBar(Instrument.us("AAA"), T.minusDays(i), c, c, c, c, null, 1, null, null, null, null, false));
        }
        return AdjustedPrices.build(bars, List.of(), List.of());
    }

    private static List<QuarterFacts> quarters(int n, QuarterFacts.Template template) {
        List<QuarterFacts> qs = new ArrayList<>();
        LocalDate end = LocalDate.of(2023, 3, 31);
        for (int i = 0; i < n; i++) {
            qs.add(new QuarterFacts(end, false, template, end.plusDays(30), 100.0 + i, 40.0, 10.0, 1.0 + i * 0.1, 500.0, 15.0));
            end = end.plusMonths(3).withDayOfMonth(end.plusMonths(3).lengthOfMonth());
        }
        return qs;
    }

    private static SelectionDataset.ValuationSeries valuation(LocalDate d, double pe, double pb, double ps) {
        return new SelectionDataset.ValuationSeries(new LocalDate[] {d}, new double[] {pe}, new double[] {pb}, new double[] {ps});
    }

    private static StockInputs in(String type, String sector, AdjustedPrices p, List<QuarterFacts> q, SelectionDataset.ValuationSeries v) {
        return SelectionDataset.inputs(new SelectionDataset.Stock(ROW, type, sector, q, p, v), T);
    }

    @Test
    void 普通股齐全时合格_四类指标都有() {
        StockInputs s = in("COMMON", "IT", prices(50, 400), quarters(9, QuarterFacts.Template.STANDARD), valuation(T, 20, 4, 5));

        assertThat(s.exclusion()).isNull();
        assertThat(s.metrics()).containsKeys(Metric.EARNINGS_YIELD, Metric.REVENUE_GROWTH, Metric.GROSS_MARGIN, Metric.MOMENTUM_12M);
        assertThat(s.metrics().get(Metric.EARNINGS_YIELD)).isCloseTo(0.05, within(1e-12));
    }

    /** 排除原因按先后判定：类型 → 板块 → 价格 → 季报。ADR、REIT、NY REG SHRS、查不到的都按类型排除（用户 10-11 / 10-12 拍板）。 */
    @Test
    void 证券类型不是COMMON或查不到的排除() {
        for (String t : new String[] {"ADR", "REIT", "NY REG SHRS"}) {
            StockInputs s = in(t, "IT", prices(50, 400), quarters(9, QuarterFacts.Template.STANDARD), valuation(T, 20, 4, 5));
            assertThat(s.exclusion()).isEqualTo(Exclusion.SECURITY_TYPE);
            assertThat(s.exclusionDetail()).isEqualTo(t);
        }
        StockInputs unknown = in(null, "IT", prices(50, 400), quarters(9, QuarterFacts.Template.STANDARD), valuation(T, 20, 4, 5));
        assertThat(unknown.exclusion()).isEqualTo(Exclusion.SECURITY_TYPE);
        assertThat(unknown.exclusionDetail()).contains("不猜");
    }

    @Test
    void 没板块_没价格_股价低于十美元_季报不足八季() {
        assertThat(in("COMMON", null, prices(50, 400), quarters(9, QuarterFacts.Template.STANDARD), valuation(T, 20, 4, 5)).exclusion())
                .isEqualTo(Exclusion.NO_SECTOR);
        assertThat(in("COMMON", "IT", AdjustedPrices.invalid("拆股缺比例"), quarters(9, QuarterFacts.Template.STANDARD),
                valuation(T, 20, 4, 5)).exclusion()).isEqualTo(Exclusion.NO_PRICE);
        assertThat(in("COMMON", "IT", prices(9.5, 400), quarters(9, QuarterFacts.Template.STANDARD), valuation(T, 20, 4, 5)).exclusion())
                .isEqualTo(Exclusion.PRICE_BELOW_MIN);
        StockInputs few = in("COMMON", "IT", prices(50, 400), quarters(7, QuarterFacts.Template.STANDARD), valuation(T, 20, 4, 5));
        assertThat(few.exclusion()).isEqualTo(Exclusion.INSUFFICIENT_HISTORY);
        assertThat(few.exclusionDetail()).contains("7 季");
    }

    @Test
    void 亏损的PE给负的盈利收益率_PB为零与PS非正的不给() {
        StockInputs s = in("COMMON", "IT", prices(50, 400), quarters(9, QuarterFacts.Template.STANDARD), valuation(T, -25, 0, -1));

        assertThat(s.metrics().get(Metric.EARNINGS_YIELD)).isCloseTo(-0.04, within(1e-12));
        assertThat(s.metrics()).doesNotContainKeys(Metric.BOOK_YIELD, Metric.SALES_YIELD);
    }

    @Test
    void 估值超过七天没更新算缺() {
        StockInputs s = in("COMMON", "IT", prices(50, 400), quarters(9, QuarterFacts.Template.STANDARD), valuation(T.minusDays(8), 20, 4, 5));

        assertThat(s.metrics()).doesNotContainKeys(Metric.EARNINGS_YIELD, Metric.BOOK_YIELD, Metric.SALES_YIELD);
    }

    @Test
    void 银行模板不适用毛利率与自由现金流利润率() {
        StockInputs s = in("COMMON", "Financials", prices(50, 400), quarters(9, QuarterFacts.Template.BANK), valuation(T, 12, 1.5, 3));

        assertThat(s.applicable()).doesNotContain(Metric.GROSS_MARGIN, Metric.FCF_MARGIN).contains(Metric.ROE);
        assertThat(s.metrics()).doesNotContainKeys(Metric.GROSS_MARGIN, Metric.FCF_MARGIN);
    }

    @Test
    void 价格历史不够一年时十二个月动能缺() {
        StockInputs s = in("COMMON", "IT", prices(50, 200), quarters(9, QuarterFacts.Template.STANDARD), valuation(T, 20, 4, 5));

        assertThat(s.metrics()).containsKey(Metric.MOMENTUM_3M).doesNotContainKey(Metric.MOMENTUM_12M);
    }
}
