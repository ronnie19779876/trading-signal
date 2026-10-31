package org.jdkxx.trader.core.selection;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
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

/**
 * Bulk loader for quant selection scoring inputs (§25.5). Daily bars are fetched {@value
 * #BAR_CHUNK} instruments at a time and dropped once the price series is built, to bound memory;
 * valuations are streamed row by row into arrays. Quarterly report availability dates are resolved
 * here once (nearest-date match against announcement dates, plus the fallback rule).
 */
public class SelectionDataLoader {

  static final int BAR_CHUNK = 50;

  /** 12-month momentum looks back 252 trading days; plus some margin */
  static final int PRICE_LOOKBACK_DAYS = 400;

  private final SelectionTargets targets;
  private final SelectionSectors sectors;
  private final SelectionInputRepository inputs;
  private final SelectionSecurityRepository securities;
  private final DailyBarRepository dailyBars;
  private final RehabFactorRepository rehabs;
  private final TradingDayRepository days;

  public SelectionDataLoader(
      SelectionTargets targets,
      SelectionSectors sectors,
      SelectionInputRepository inputs,
      SelectionSecurityRepository securities,
      DailyBarRepository dailyBars,
      RehabFactorRepository rehabs,
      TradingDayRepository days) {
    this.targets = targets;
    this.sectors = sectors;
    this.inputs = inputs;
    this.securities = securities;
    this.dailyBars = dailyBars;
    this.rehabs = rehabs;
    this.days = days;
  }

  /** Loads every input needed to score each day in [from, to]. */
  public SelectionDataset load(LocalDate from, LocalDate to) {
    return load(from, to, to);
  }

  /**
   * As {@link #load(LocalDate, LocalDate)}, with prices loaded through {@code pricesTo} (the
   * historical test needs bars past the last scoring day for forward returns, §30.3). Scoring on a
   * day d still only looks at data on or before d.
   */
  public SelectionDataset load(LocalDate from, LocalDate to, LocalDate pricesTo) {
    List<InstrumentRow> rows = targets.all();
    Map<Long, InstrumentRow> byId = new LinkedHashMap<>();
    rows.forEach(r -> byId.put(r.id(), r));
    Map<Long, String> sector = sectors.of(rows);
    Map<Long, String> types = securities.types();
    // The calendar reaches 200 days past `to` so the statutory-deadline fallback can roll a
    // non-trading day forward
    TreeSet<LocalDate> calendar =
        new TreeSet<>(days.between(Market.US, LocalDate.of(2013, 1, 1), to.plusDays(200)));

    Map<Long, List<QuarterFacts>> quarters =
        quarters(byId.keySet(), inputs.pubDates(byId.keySet()), calendar);
    Map<Long, SelectionDataset.ValuationSeries> valuations =
        valuations(byId.keySet(), from.minusDays(SelectionDataset.STALE_DAYS), to);
    Map<Long, List<SplitAdjustedBar>> ibkr = inputs.seamPassedIbkrBars(byId.keySet());

    Map<Long, AdjustedPrices> prices = new HashMap<>();
    List<Long> ids = new ArrayList<>(byId.keySet());
    LocalDate priceFrom = from.minusDays(PRICE_LOOKBACK_DAYS);
    for (int i = 0; i < ids.size(); i += BAR_CHUNK) {
      Map<Long, Instrument> chunk = new LinkedHashMap<>();
      for (Long id : ids.subList(i, Math.min(ids.size(), i + BAR_CHUNK))) {
        chunk.put(id, byId.get(id).instrument());
      }
      // The IBKR segment is stitched onto Futu by the ratio on their overlap (the end of the IBKR
      // segment, about 4 months after the Futu start): Futu bars are fetched at least to the end of
      // the IBKR segment, and IBKR bars are not cut off at `to`. This is not look-ahead - momentum
      // only uses prices on or before t, the stitching ratio is a constant scale factor, and
      // unadjusted prices are reconstructed from the real split events.
      LocalDate barsTo = pricesTo.isAfter(to) ? pricesTo : to;
      for (Long id : chunk.keySet()) {
        List<SplitAdjustedBar> ib = ibkr.getOrDefault(id, List.of());
        if (!ib.isEmpty() && ib.getLast().tradeDate().isAfter(barsTo)) {
          barsTo = ib.getLast().tradeDate();
        }
      }
      Map<Long, List<DailyBar>> bars = dailyBars.findMany(chunk, priceFrom, barsTo);
      Map<Long, List<RehabFactor>> factors = rehabs.findMany(chunk);
      for (Long id : chunk.keySet()) {
        List<SplitAdjustedBar> ib =
            ibkr.getOrDefault(id, List.of()).stream()
                .filter(b -> !b.tradeDate().isBefore(priceFrom))
                .toList();
        prices.put(
            id,
            AdjustedPrices.build(
                bars.getOrDefault(id, List.of()), factors.getOrDefault(id, List.of()), ib));
      }
    }

    List<SelectionDataset.Stock> stocks = new ArrayList<>(rows.size());
    for (InstrumentRow r : rows) {
      stocks.add(
          new SelectionDataset.Stock(
              r,
              types.get(r.id()),
              sector.get(r.id()),
              quarters.getOrDefault(r.id(), List.of()),
              prices.get(r.id()),
              valuations.getOrDefault(r.id(), SelectionDataset.ValuationSeries.EMPTY)));
    }
    return new SelectionDataset(stocks);
  }

  /**
   * Quarterly fact rows to one QuarterFacts per quarter (true period end = stored period end + 1
   * day), then resolves the availability dates. Duplicate rows for the same quarter are dropped
   * first ({@link FundamentalMetrics#dedupe}) so TTM sums never double-count.
   */
  Map<Long, List<QuarterFacts>> quarters(
      java.util.Collection<Long> ids,
      Map<Long, List<LocalDate>> pubDates,
      TreeSet<LocalDate> calendar) {
    Map<Long, TreeMap<LocalDate, Map<Long, Double>>> income = new HashMap<>();
    Map<Long, TreeMap<LocalDate, Map<Long, Double>>> balance = new HashMap<>();
    Map<Long, TreeMap<LocalDate, Map<Long, Double>>> cash = new HashMap<>();
    Map<Long, Map<LocalDate, Boolean>> q4 = new HashMap<>();
    for (SelectionInputRepository.FactRow f : inputs.quarterlyFacts(ids)) {
      Map<Long, TreeMap<LocalDate, Map<Long, Double>>> target =
          switch (f.statement()) {
            case "INCOME" -> income;
            case "BALANCE_SHEET" -> balance;
            default -> cash;
          };
      target
          .computeIfAbsent(f.instrumentId(), k -> new TreeMap<>())
          .computeIfAbsent(f.periodEnd(), k -> new HashMap<>())
          .put(f.fieldId(), f.value());
      if ("INCOME".equals(f.statement())) {
        q4.computeIfAbsent(f.instrumentId(), k -> new HashMap<>())
            .put(f.periodEnd(), f.periodText().endsWith("Q4"));
      }
    }
    Map<Long, List<QuarterFacts>> out = new HashMap<>();
    for (Map.Entry<Long, TreeMap<LocalDate, Map<Long, Double>>> e : income.entrySet()) {
      long id = e.getKey();
      List<QuarterFacts> list = new ArrayList<>();
      for (Map.Entry<LocalDate, Map<Long, Double>> q : e.getValue().entrySet()) {
        Map<Long, Double> inc = q.getValue();
        Map<Long, Double> bs =
            balance.getOrDefault(id, new TreeMap<>()).getOrDefault(q.getKey(), Map.of());
        Map<Long, Double> cf =
            cash.getOrDefault(id, new TreeMap<>()).getOrDefault(q.getKey(), Map.of());
        // Statement template from the field ids present: 9xxx = bank, 10xxx = insurer, otherwise
        // standard. Field ids mean different things per statement type, so each value is looked up
        // per template (8xxx / 9xxx / 10xxx)
        QuarterFacts.Template template =
            inc.keySet().stream().anyMatch(k -> k >= 9000 && k < 10000)
                ? QuarterFacts.Template.BANK
                : inc.keySet().stream().anyMatch(k -> k >= 10000 && k < 11000)
                    ? QuarterFacts.Template.INSURANCE
                    : QuarterFacts.Template.STANDARD;
        list.add(
            new QuarterFacts(
                q.getKey().plusDays(1),
                q4.get(id).getOrDefault(q.getKey(), false),
                template,
                null,
                first(inc, 8002, 9002, 10002),
                first(inc, 8004),
                first(inc, 8043, 9046, 10047),
                first(inc, 8048, 9051, 10052),
                first(bs, 8085, 9070, 10065),
                first(cf, 8072, 9078, 10078)));
      }
      out.put(
          id,
          QuarterAvailability.resolve(
              FundamentalMetrics.dedupe(list), pubDates.getOrDefault(id, List.of()), calendar));
    }
    return out;
  }

  private Map<Long, SelectionDataset.ValuationSeries> valuations(
      java.util.Collection<Long> ids, LocalDate from, LocalDate to) {
    Map<Long, List<SelectionInputRepository.ValuationRow>> rows = new HashMap<>();
    inputs.forEachValuation(
        ids, from, to, r -> rows.computeIfAbsent(r.instrumentId(), k -> new ArrayList<>()).add(r));
    Map<Long, SelectionDataset.ValuationSeries> out = new HashMap<>();
    rows.forEach(
        (id, list) -> {
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

  /**
   * First non-null value among the given field ids (standard / bank / insurer variants of the same
   * line).
   */
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
