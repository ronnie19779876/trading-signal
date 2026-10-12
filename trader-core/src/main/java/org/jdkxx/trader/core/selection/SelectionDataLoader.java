package org.jdkxx.trader.core.selection;

import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.RehabFactor;
import org.jdkxx.trader.domain.selection.AdjustedPrices;
import org.jdkxx.trader.domain.selection.FundamentalMetrics;
import org.jdkxx.trader.domain.selection.QuarterAvailability;
import org.jdkxx.trader.domain.selection.QuarterFacts;
import org.jdkxx.trader.domain.selection.SplitAdjustedBar;
import org.jdkxx.trader.storage.marketdata.DailyBarRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.jdkxx.trader.storage.marketdata.RehabFactorRepository;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;
import org.jdkxx.trader.storage.selection.SelectionInputRepository;
import org.jdkxx.trader.storage.selection.SelectionSecurityRepository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 批量加载量化选股的打分输入（§25.5）。日 K 按 {@value #BAR_CHUNK} 只一组取、算完价格序列即丢，控制内存；
 * 估值逐行回调装进数组。季报可用日在这里一次解析好（按日期就近匹配公告日 + 兜底规则）。
 */
public class SelectionDataLoader {

    static final int BAR_CHUNK = 50;
    /** 12 个月动能要往前 252 个交易日，再留余量 */
    static final int PRICE_LOOKBACK_DAYS = 400;

    private final SelectionTargets targets;
    private final SelectionSectors sectors;
    private final SelectionInputRepository inputs;
    private final SelectionSecurityRepository securities;
    private final DailyBarRepository dailyBars;
    private final RehabFactorRepository rehabs;
    private final TradingDayRepository days;

    public SelectionDataLoader(SelectionTargets targets, SelectionSectors sectors, SelectionInputRepository inputs,
                               SelectionSecurityRepository securities, DailyBarRepository dailyBars, RehabFactorRepository rehabs,
                               TradingDayRepository days) {
        this.targets = targets;
        this.sectors = sectors;
        this.inputs = inputs;
        this.securities = securities;
        this.dailyBars = dailyBars;
        this.rehabs = rehabs;
        this.days = days;
    }

    /** 加载能覆盖 [from, to] 每一天打分所需的全部输入。 */
    public SelectionDataset load(LocalDate from, LocalDate to) {
        List<InstrumentRow> rows = targets.all();
        Map<Long, InstrumentRow> byId = new LinkedHashMap<>();
        rows.forEach(r -> byId.put(r.id(), r));
        Map<Long, String> sector = sectors.of(rows);
        Map<Long, String> types = securities.types();
        TreeSet<LocalDate> calendar = new TreeSet<>(days.between(Market.US, LocalDate.of(2013, 1, 1), to.plusDays(200)));

        Map<Long, List<QuarterFacts>> quarters = quarters(byId.keySet(), inputs.pubDates(byId.keySet()), calendar);
        Map<Long, SelectionDataset.ValuationSeries> valuations = valuations(byId.keySet(), from.minusDays(SelectionDataset.STALE_DAYS), to);
        Map<Long, List<SplitAdjustedBar>> ibkr = inputs.seamPassedIbkrBars(byId.keySet());

        Map<Long, AdjustedPrices> prices = new HashMap<>();
        List<Long> ids = new ArrayList<>(byId.keySet());
        LocalDate priceFrom = from.minusDays(PRICE_LOOKBACK_DAYS);
        for (int i = 0; i < ids.size(); i += BAR_CHUNK) {
            Map<Long, Instrument> chunk = new LinkedHashMap<>();
            for (Long id : ids.subList(i, Math.min(ids.size(), i + BAR_CHUNK))) {
                chunk.put(id, byId.get(id).instrument());
            }
            // 盈透段要在与富途的重叠日（盈透段末尾，约富途起点后 4 个月）按比值拼接：富途日 K 至少取到盈透段结束，
            // 盈透日线也不按 to 截断。不构成偷看——动能只用 ≤ t 的价格，拼接比值是常数缩放，不复权价由真实的拆股事件还原。
            LocalDate barsTo = to;
            for (Long id : chunk.keySet()) {
                List<SplitAdjustedBar> ib = ibkr.getOrDefault(id, List.of());
                if (!ib.isEmpty() && ib.getLast().tradeDate().isAfter(barsTo)) {
                    barsTo = ib.getLast().tradeDate();
                }
            }
            Map<Long, List<DailyBar>> bars = dailyBars.findMany(chunk, priceFrom, barsTo);
            Map<Long, List<RehabFactor>> factors = rehabs.findMany(chunk);
            for (Long id : chunk.keySet()) {
                List<SplitAdjustedBar> ib = ibkr.getOrDefault(id, List.of()).stream()
                        .filter(b -> !b.tradeDate().isBefore(priceFrom)).toList();
                prices.put(id, AdjustedPrices.build(bars.getOrDefault(id, List.of()), factors.getOrDefault(id, List.of()), ib));
            }
        }

        List<SelectionDataset.Stock> stocks = new ArrayList<>(rows.size());
        for (InstrumentRow r : rows) {
            stocks.add(new SelectionDataset.Stock(r, types.get(r.id()), sector.get(r.id()), quarters.getOrDefault(r.id(), List.of()),
                    prices.get(r.id()), valuations.getOrDefault(r.id(), SelectionDataset.ValuationSeries.EMPTY)));
        }
        return new SelectionDataset(stocks);
    }

    /** 季报数据项 → 每季一条 QuarterFacts（真实期末 = 库内期末 + 1 天），再解析可用日。 */
    Map<Long, List<QuarterFacts>> quarters(java.util.Collection<Long> ids, Map<Long, List<LocalDate>> pubDates, TreeSet<LocalDate> calendar) {
        Map<Long, TreeMap<LocalDate, Map<Long, Double>>> income = new HashMap<>();
        Map<Long, TreeMap<LocalDate, Map<Long, Double>>> balance = new HashMap<>();
        Map<Long, TreeMap<LocalDate, Map<Long, Double>>> cash = new HashMap<>();
        Map<Long, Map<LocalDate, Boolean>> q4 = new HashMap<>();
        for (SelectionInputRepository.FactRow f : inputs.quarterlyFacts(ids)) {
            Map<Long, TreeMap<LocalDate, Map<Long, Double>>> target = switch (f.statement()) {
                case "INCOME" -> income;
                case "BALANCE_SHEET" -> balance;
                default -> cash;
            };
            target.computeIfAbsent(f.instrumentId(), k -> new TreeMap<>()).computeIfAbsent(f.periodEnd(), k -> new HashMap<>())
                    .put(f.fieldId(), f.value());
            if ("INCOME".equals(f.statement())) {
                q4.computeIfAbsent(f.instrumentId(), k -> new HashMap<>()).put(f.periodEnd(), f.periodText().endsWith("Q4"));
            }
        }
        Map<Long, List<QuarterFacts>> out = new HashMap<>();
        for (Map.Entry<Long, TreeMap<LocalDate, Map<Long, Double>>> e : income.entrySet()) {
            long id = e.getKey();
            List<QuarterFacts> list = new ArrayList<>();
            for (Map.Entry<LocalDate, Map<Long, Double>> q : e.getValue().entrySet()) {
                Map<Long, Double> inc = q.getValue();
                Map<Long, Double> bs = balance.getOrDefault(id, new TreeMap<>()).getOrDefault(q.getKey(), Map.of());
                Map<Long, Double> cf = cash.getOrDefault(id, new TreeMap<>()).getOrDefault(q.getKey(), Map.of());
                QuarterFacts.Template template = inc.keySet().stream().anyMatch(k -> k >= 9000 && k < 10000) ? QuarterFacts.Template.BANK
                        : inc.keySet().stream().anyMatch(k -> k >= 10000 && k < 11000) ? QuarterFacts.Template.INSURANCE
                        : QuarterFacts.Template.STANDARD;
                list.add(new QuarterFacts(q.getKey().plusDays(1), q4.get(id).getOrDefault(q.getKey(), false), template, null,
                        first(inc, 8002, 9002, 10002), first(inc, 8004), first(inc, 8043, 9046, 10047), first(inc, 8048, 9051, 10052),
                        first(bs, 8085, 9070, 10065), first(cf, 8072, 9078, 10078)));
            }
            out.put(id, QuarterAvailability.resolve(FundamentalMetrics.dedupe(list), pubDates.getOrDefault(id, List.of()), calendar));
        }
        return out;
    }

    private Map<Long, SelectionDataset.ValuationSeries> valuations(java.util.Collection<Long> ids, LocalDate from, LocalDate to) {
        Map<Long, List<SelectionInputRepository.ValuationRow>> rows = new HashMap<>();
        inputs.forEachValuation(ids, from, to, r -> rows.computeIfAbsent(r.instrumentId(), k -> new ArrayList<>()).add(r));
        Map<Long, SelectionDataset.ValuationSeries> out = new HashMap<>();
        rows.forEach((id, list) -> {
            int n = list.size();
            LocalDate[] d = new LocalDate[n];
            double[] pe = new double[n];
            double[] pb = new double[n];
            double[] ps = new double[n];
            for (int i = 0; i < n; i++) {
                SelectionInputRepository.ValuationRow r = list.get(i);
                d[i] = r.tradeDate();
                pe[i] = r.pe() == null ? Double.NaN : r.pe();
                pb[i] = r.pb() == null ? Double.NaN : r.pb();
                ps[i] = r.ps() == null ? Double.NaN : r.ps();
            }
            out.put(id, new SelectionDataset.ValuationSeries(d, pe, pb, ps));
        });
        return out;
    }

    private static Double first(Map<Long, Double> fields, long... ids) {
        for (long id : ids) {
            Double v = fields.get(id);
            if (v != null) {
                return v;
            }
        }
        return null;
    }
}
