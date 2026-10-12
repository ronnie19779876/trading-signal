package org.jdkxx.trader.core.selection;

import org.jdkxx.trader.domain.selection.AdjustedPrices;
import org.jdkxx.trader.domain.selection.Exclusion;
import org.jdkxx.trader.domain.selection.FundamentalMetrics;
import org.jdkxx.trader.domain.selection.Metric;
import org.jdkxx.trader.domain.selection.QuarterFacts;
import org.jdkxx.trader.domain.selection.StockInputs;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 量化选股打分的已加载输入（§25.5 时点口径）。{@link #inputsOn} 只用判定日 t 及之前可得的数据：
 * 季报看可用日、估值取 ≤ t 的最近一行（超过 {@value #STALE_DAYS} 天算缺）、价格取 ≤ t 的最后一根。
 */
public final class SelectionDataset {

    /** 股价下限（不复权，§25.3 第 4 条） */
    static final double PRICE_MIN = 10;
    /** 已公告季报下限（§25.3 第 5 条） */
    static final int MIN_QUARTERS = 8;
    /** 价格、估值离 t 最多多少个自然日还算「当天有」（约 5 个交易日） */
    static final int STALE_DAYS = 7;
    static final int[] MOMENTUM_DAYS = {63, 126, 252};
    static final String COMMON = "COMMON";
    private static final Set<Metric> FINANCIAL_NOT_APPLICABLE = EnumSet.of(Metric.GROSS_MARGIN, Metric.FCF_MARGIN);

    /** 一只标的的逐日估值，按日期升序。 */
    record ValuationSeries(LocalDate[] dates, double[] pe, double[] pb, double[] ps) {
        static final ValuationSeries EMPTY = new ValuationSeries(new LocalDate[0], new double[0], new double[0], new double[0]);

        int indexAt(LocalDate t) {
            int i = Arrays.binarySearch(dates, t);
            if (i < 0) {
                i = -i - 2;
            }
            return i < 0 || ChronoUnit.DAYS.between(dates[i], t) > STALE_DAYS ? -1 : i;
        }
    }

    /**
     * @param securityType 盈透 stockType；查不到为 null
     * @param sector       GICS 板块；没有为 null
     * @param quarters     已解析可用日的季报，按期末升序
     */
    record Stock(InstrumentRow row, String securityType, String sector, List<QuarterFacts> quarters, AdjustedPrices prices,
                 ValuationSeries valuations) {
    }

    private final List<Stock> stocks;

    SelectionDataset(List<Stock> stocks) {
        this.stocks = List.copyOf(stocks);
    }

    List<Stock> stocks() {
        return stocks;
    }

    List<StockInputs> inputsOn(LocalDate t) {
        List<StockInputs> out = new ArrayList<>(stocks.size());
        for (Stock s : stocks) {
            out.add(inputs(s, t));
        }
        return out;
    }

    static StockInputs inputs(Stock s, LocalDate t) {
        Map<Metric, Double> m = new EnumMap<>(Metric.class);
        m.putAll(FundamentalMetrics.compute(s.quarters(), t));

        int v = s.valuations().indexAt(t);
        if (v >= 0) {
            double pe = s.valuations().pe()[v];
            double pb = s.valuations().pb()[v];
            double ps = s.valuations().ps()[v];
            if (Double.isFinite(pe) && pe != 0) {
                m.put(Metric.EARNINGS_YIELD, 1 / pe);   // 亏损为负，保留
            }
            if (Double.isFinite(pb) && pb != 0) {
                m.put(Metric.BOOK_YIELD, 1 / pb);
            }
            if (Double.isFinite(ps) && ps > 0) {
                m.put(Metric.SALES_YIELD, 1 / ps);
            }
        }

        AdjustedPrices p = s.prices();
        int idx = p.valid() ? p.indexAt(t, STALE_DAYS) : -1;
        if (idx >= 0) {
            Metric[] mom = {Metric.MOMENTUM_3M, Metric.MOMENTUM_6M, Metric.MOMENTUM_12M};
            for (int i = 0; i < mom.length; i++) {
                double r = p.returnOver(idx, MOMENTUM_DAYS[i]);
                if (Double.isFinite(r)) {
                    m.put(mom[i], r);
                }
            }
        }

        Set<Metric> applicable = EnumSet.allOf(Metric.class);
        QuarterFacts latest = latestAvailable(s.quarters(), t);
        if (latest != null && latest.template().financial()) {
            applicable.removeAll(FINANCIAL_NOT_APPLICABLE);
        }

        Exclusion ex = null;
        String detail = null;
        int known = FundamentalMetrics.availableCount(s.quarters(), t);
        if (s.securityType() == null) {
            ex = Exclusion.SECURITY_TYPE;
            detail = "盈透证券类型未知（不猜）";
        } else if (!COMMON.equals(s.securityType())) {
            ex = Exclusion.SECURITY_TYPE;
            detail = s.securityType();
        } else if (s.sector() == null) {
            ex = Exclusion.NO_SECTOR;
            detail = "没有 GICS 板块，覆盖表里也没有";
        } else if (!p.valid()) {
            ex = Exclusion.NO_PRICE;
            detail = "价格序列作废：" + p.problem();
        } else if (idx < 0) {
            ex = Exclusion.NO_PRICE;
            detail = "截至当天 " + STALE_DAYS + " 天内没有收盘价";
        } else if (p.rawClose(idx) < PRICE_MIN) {
            ex = Exclusion.PRICE_BELOW_MIN;
            detail = "不复权收盘 " + Math.round(p.rawClose(idx) * 100) / 100.0 + " < " + PRICE_MIN;
        } else if (known < MIN_QUARTERS) {
            ex = Exclusion.INSUFFICIENT_HISTORY;
            detail = "已公告季报 " + known + " 季，不足 " + MIN_QUARTERS;
        }
        return new StockInputs(s.row().id(), s.row().symbol(), s.sector(), ex, detail, m, applicable);
    }

    private static QuarterFacts latestAvailable(List<QuarterFacts> quarters, LocalDate t) {
        QuarterFacts latest = null;
        for (QuarterFacts q : quarters) {
            if (q.availableOn() != null && !q.availableOn().isAfter(t)) {
                latest = q;
            }
        }
        return latest;
    }
}
