package org.jdkxx.trader.core.marketdata.valuation;

import org.jdkxx.trader.core.marketdata.InstrumentDirectory;
import org.jdkxx.trader.domain.FinancialReport;
import org.jdkxx.trader.domain.FinancialStatement;
import org.jdkxx.trader.domain.ValuationSnapshot;
import org.jdkxx.trader.domain.valuation.SotpBasis;
import org.jdkxx.trader.storage.marketdata.ConstituentRow;
import org.jdkxx.trader.storage.marketdata.DailyBarRepository;
import org.jdkxx.trader.storage.marketdata.FinancialRepository;
import org.jdkxx.trader.storage.marketdata.IndexConstituentRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.jdkxx.trader.storage.marketdata.ValuationRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 分部估值的公司级底座：现价、股数、净现金、当前营收与净利率、本益比基准、适用性。全部从库里取，不用手填。
 *
 * <p><b>这里只给数，不替使用者做选择。</b>股数三种口径（券商给的流通股、市值 ÷ 现价、归母净利 ÷ 稀释每股收益）
 * 实测对不上（2026-09-19 TSLA：39.5 亿 vs 35 亿，差额未核实），所以三个都给、标明来源，由使用者选。
 */
public class SotpBasisService {

    /** 资产负债表字段编号（字段编号的含义随报表类型不同，必须按报表定位）。 */
    private static final long BS_CASH_AND_SHORT_TERM = 8003L;   // 现金及现金等价物和短期投资
    private static final long BS_SHORT_TERM_BORROWING = 8058L;  // 短期借款
    private static final long BS_SHORT_TERM_LEASE = 8060L;      // 短期融资租赁负债
    private static final long BS_LONG_TERM_BORROWING = 8069L;   // 长期借款
    private static final long BS_LONG_TERM_LEASE = 8070L;       // 长期融资租赁负债

    private static final long INCOME_REVENUE = 8001L;           // 总收入
    private static final long INCOME_NET_TO_PARENT = 8043L;     // 归属于母公司股东净利润
    private static final long MAIN_NET_MARGIN = 14005L;         // 归母净利率（TTM，百分数）

    /** 本益比基准的回看年数。 */
    private static final int PE_LOOKBACK_YEARS = 5;

    private final InstrumentDirectory directory;
    private final DailyBarRepository bars;
    private final ValuationRepository valuations;
    private final FinancialRepository financials;
    private final IndexConstituentRepository constituents;

    public SotpBasisService(InstrumentDirectory directory, DailyBarRepository bars, ValuationRepository valuations,
                            FinancialRepository financials, IndexConstituentRepository constituents) {
        this.directory = directory;
        this.bars = bars;
        this.valuations = valuations;
        this.financials = financials;
        this.constituents = constituents;
    }

    /**
     * 底座与适用性。
     *
     * @param shares          三种口径的股数，全给，不替使用者选
     * @param netCash         净现金拆解
     * @param ttmRevenue      最近四个季度营收之和；凑不齐四季为 null
     * @param ttmNetIncome    最近四个季度归母净利之和；凑不齐四季为 null
     * @param netMarginTtm    券商给的归母净利率（TTM），<b>小数</b>
     * @param peMedian        近 5 年日 K 市盈率正值的中位数
     * @param applicability   适用性判断
     */
    public record Basis(String symbol, String name, LocalDate priceDate, Double price, Shares shares, NetCash netCash,
                        Double ttmRevenue, Double ttmNetIncome, Double netMarginTtm, Double peMedian,
                        Applicability applicability) {

        /** 换成计算核心要的底座。股数与净现金的目标年预测值由使用者填，不在这里。 */
        public SotpBasis forCalculator() {
            return new SotpBasis(price == null ? 0 : price, peMedian, ttmNetIncome, netMarginTtm);
        }
    }

    /**
     * @param outstanding    券商给的流通股数
     * @param byMarketCap    市值 ÷ 现价
     * @param byDilutedEps   归母净利 ÷ 稀释每股收益
     */
    public record Shares(Long outstanding, Double byMarketCap, Double byDilutedEps) {
    }

    /**
     * @param cashAndShortTerm 现金及现金等价物和短期投资
     * @param borrowings       短期借款 + 长期借款
     * @param leases           短期 + 长期融资租赁负债，<b>默认不计入有息负债</b>，使用者可选择计入
     * @param netCash          cashAndShortTerm − borrowings
     * @param periodText       取自哪一期
     */
    public record NetCash(Double cashAndShortTerm, Double borrowings, Double leases, Double netCash, String periodText) {
    }

    /**
     * @param verdict APPLICABLE / CAUTION / NOT_APPLICABLE
     * @param reasons 判断依据，逐条给原因，不只给结论
     */
    public record Applicability(String verdict, List<String> reasons) {
    }

    public Basis of(String symbol) {
        InstrumentRow row = directory.require(symbol);
        long id = row.id();

        BigDecimal close = bars.latestClose(id).orElse(null);
        Double price = close == null ? null : close.doubleValue();
        LocalDate priceDate = bars.latestDate(id).orElse(null);

        ValuationSnapshot snap = latestSnapshot(row, id);
        List<FinancialReport> income = financials.find(row.instrument(), id, FinancialStatement.INCOME, 8);
        List<FinancialReport> balance = financials.find(row.instrument(), id, FinancialStatement.BALANCE_SHEET, 1);
        List<FinancialReport> main = financials.find(row.instrument(), id, FinancialStatement.MAIN_INDEX, 1);

        Double ttmRevenue = ttm(income, INCOME_REVENUE);
        Double ttmNetIncome = ttm(income, INCOME_NET_TO_PARENT);
        Double netMargin = percentToRate(value(main, MAIN_NET_MARGIN));
        Double peMedian = bars.peMedian(id, LocalDate.now().minusYears(PE_LOOKBACK_YEARS)).orElse(null);

        return new Basis(row.symbol(), row.nameCn() == null ? row.name() : row.nameCn(), priceDate, price,
                shares(snap, price, ttmNetIncome), netCash(balance), ttmRevenue, ttmNetIncome, netMargin, peMedian,
                applicability(row, income, ttmNetIncome, peMedian));
    }

    private ValuationSnapshot latestSnapshot(InstrumentRow row, long id) {
        LocalDate to = LocalDate.now();
        List<ValuationSnapshot> list = valuations.find(row.instrument(), id, to.minusDays(30), to);
        return list.isEmpty() ? null : list.get(list.size() - 1);
    }

    private Shares shares(ValuationSnapshot snap, Double price, Double ttmNetIncome) {
        Long outstanding = snap == null ? null : snap.outstandingShares();
        Double byCap = null;
        if (snap != null && snap.marketCap() != null && price != null && price > 0) {
            byCap = snap.marketCap().doubleValue() / price;
        }
        Double byEps = null;
        if (snap != null && snap.eps() != null && snap.eps().doubleValue() != 0 && ttmNetIncome != null) {
            byEps = ttmNetIncome / snap.eps().doubleValue();
        }
        return new Shares(outstanding, byCap, byEps);
    }

    private NetCash netCash(List<FinancialReport> balance) {
        if (balance.isEmpty()) {
            return new NetCash(null, null, null, null, null);
        }
        FinancialReport r = balance.get(0);
        Double cash = value(balance, BS_CASH_AND_SHORT_TERM);
        Double borrowings = sum(value(balance, BS_SHORT_TERM_BORROWING), value(balance, BS_LONG_TERM_BORROWING));
        Double leases = sum(value(balance, BS_SHORT_TERM_LEASE), value(balance, BS_LONG_TERM_LEASE));
        Double net = cash == null ? null : cash - (borrowings == null ? 0 : borrowings);
        return new NetCash(cash, borrowings, leases, net, r.periodText());
    }

    /**
     * 适用性。<b>判断"该不该有财报"看有没有取到，不看 {@code secType}</b>——券商把美股 REITs 也归为 Trust，
     * 按类型过滤会把 25 只有财报的 REITs 一起漏掉。
     */
    private Applicability applicability(InstrumentRow row, List<FinancialReport> income, Double ttmNetIncome, Double peMedian) {
        List<String> reasons = new ArrayList<>();
        if (income.isEmpty()) {
            reasons.add("取不到财报，基金与指数没有分部利润可拆");
            return new Applicability("NOT_APPLICABLE", List.copyOf(reasons));
        }
        if (ttmNetIncome != null && ttmNetIncome <= 0) {
            reasons.add("当前归母净利非正，本益比没有意义，反推不给结论");
        }
        if (peMedian == null) {
            reasons.add("近 " + PE_LOOKBACK_YEARS + " 年没有正的日 K 市盈率，取不到本益比基准");
        }
        String sector = sectorOf(row.id());
        if (sector != null) {
            String s = sector.toLowerCase();
            if (s.contains("financial")) {
                reasons.add("金融业（" + sector + "）：银行保险的估值口径是 PB + ROE，不是分部利润 × 本益比");
            } else if (s.contains("real estate")) {
                reasons.add("房地产 / REITs（" + sector + "）：口径应为 FFO，不是净利");
            }
        }
        return new Applicability(reasons.isEmpty() ? "APPLICABLE" : "CAUTION", List.copyOf(reasons));
    }

    private String sectorOf(long instrumentId) {
        return constituents.historyOf(instrumentId).stream().filter(c -> c.until() == null)
                .map(ConstituentRow::sector).filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    /** 最近四个季度之和。<b>年报与四季报期末是同一天</b>，所以只认期别以 /Q 结尾的，别按期末去重。 */
    private Double ttm(List<FinancialReport> income, long fieldId) {
        List<FinancialReport> quarters = income.stream()
                .filter(r -> r.periodText() != null && r.periodText().contains("/Q"))
                .sorted((a, b) -> b.periodEnd().compareTo(a.periodEnd()))
                .limit(4).toList();
        if (quarters.size() < 4) {
            return null;
        }
        double sum = 0;
        for (FinancialReport r : quarters) {
            Double v = value(List.of(r), fieldId);
            if (v == null) {
                return null;
            }
            sum += v;
        }
        return sum;
    }

    private Double value(List<FinancialReport> reports, long fieldId) {
        for (FinancialReport r : reports) {
            for (FinancialReport.Item item : r.items()) {
                if (item.fieldId() == fieldId) {
                    return item.value() == null ? null : item.value().doubleValue();
                }
            }
        }
        return null;
    }

    private Double sum(Double a, Double b) {
        if (a == null && b == null) {
            return null;
        }
        return (a == null ? 0 : a) + (b == null ? 0 : b);
    }

    private Double percentToRate(Double percent) {
        return percent == null ? null : percent / 100.0d;
    }

}
