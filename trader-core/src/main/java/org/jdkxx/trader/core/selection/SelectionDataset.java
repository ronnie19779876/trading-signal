package org.jdkxx.trader.core.selection;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jdkxx.trader.domain.selection.AdjustedPrices;
import org.jdkxx.trader.domain.selection.Exclusion;
import org.jdkxx.trader.domain.selection.FundamentalMetrics;
import org.jdkxx.trader.domain.selection.Metric;
import org.jdkxx.trader.domain.selection.ModelSpec;
import org.jdkxx.trader.domain.selection.QuarterFacts;
import org.jdkxx.trader.domain.selection.StockInputs;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;

/**
 * Loaded inputs for quant selection scoring (§25.5 point-in-time rules). {@link #inputsOn} only
 * uses data available on or before the scoring date t: quarterly reports by their availability
 * date, valuation from the latest row on or before t (more than {@value #STALE_DAYS} days old
 * counts as missing), price from the last bar on or before t. This is what keeps the historical
 * replay free of look-ahead.
 */
public final class SelectionDataset {

  /**
   * How many calendar days a price / valuation may lag t for F1 (about 5 trading days); the loader
   * reaches back this far for valuations
   */
  static final int STALE_DAYS = ModelSpec.F1.staleDays();

  /** Momentum windows in trading days: {look-back, skip} (skip = most recent bars left out) */
  static final Map<Metric, int[]> MOMENTUM_WINDOWS =
      new EnumMap<>(
          Map.of(
              Metric.MOMENTUM_3M, new int[] {63, 0},
              Metric.MOMENTUM_6M, new int[] {126, 0},
              Metric.MOMENTUM_12M, new int[] {252, 0},
              Metric.MOMENTUM_12_1, new int[] {252, 21}));

  static final String COMMON = "COMMON";
  private static final Set<Metric> FINANCIAL_NOT_APPLICABLE =
      EnumSet.of(Metric.GROSS_MARGIN, Metric.FCF_MARGIN);

  /** One instrument's daily valuations, in ascending date order. */
  record ValuationSeries(LocalDate[] dates, double[] pe, double[] pb, double[] ps) {
    static final ValuationSeries EMPTY =
        new ValuationSeries(new LocalDate[0], new double[0], new double[0], new double[0]);

    /**
     * Index of the latest row on or before t, or -1 when there is none or it is older than
     * staleDays.
     */
    int indexAt(LocalDate t, int staleDays) {
      int i = Arrays.binarySearch(dates, t);
      if (i < 0) {
        i = -i - 2; // binarySearch miss: -(insertion point) - 1, so this is the index just before t
      }
      return i < 0 || ChronoUnit.DAYS.between(dates[i], t) > staleDays ? -1 : i;
    }
  }

  /**
   * @param securityType IBKR stockType; null when it could not be looked up
   * @param sector GICS sector; null when there is none
   * @param quarters quarterly reports with resolved availability dates, ascending by period end
   */
  record Stock(
      InstrumentRow row,
      String securityType,
      String sector,
      List<QuarterFacts> quarters,
      AdjustedPrices prices,
      ValuationSeries valuations) {}

  private final List<Stock> stocks;

  SelectionDataset(List<Stock> stocks) {
    this.stocks = List.copyOf(stocks);
  }

  List<Stock> stocks() {
    return stocks;
  }

  List<StockInputs> inputsOn(LocalDate t) {
    return inputsOn(t, ModelSpec.F1);
  }

  List<StockInputs> inputsOn(LocalDate t, ModelSpec spec) {
    List<StockInputs> out = new ArrayList<>(stocks.size());
    for (Stock s : stocks) {
      out.add(inputs(s, t, spec));
    }
    return out;
  }

  static StockInputs inputs(Stock s, LocalDate t) {
    return inputs(s, t, ModelSpec.F1);
  }

  /**
   * One instrument's inputs on t under {@code spec}: only the spec's metrics are kept (so the
   * fingerprint of F1 is unchanged), and the exclusion thresholds come from the spec.
   */
  static StockInputs inputs(Stock s, LocalDate t, ModelSpec spec) {
    Map<Metric, Double> m = new EnumMap<>(Metric.class);
    m.putAll(FundamentalMetrics.compute(s.quarters(), t));

    int v = s.valuations().indexAt(t, spec.staleDays());
    if (v >= 0) {
      double pe = s.valuations().pe()[v];
      double pb = s.valuations().pb()[v];
      double ps = s.valuations().ps()[v];
      if (Double.isFinite(pe) && pe != 0) {
        m.put(Metric.EARNINGS_YIELD, 1 / pe); // negative when loss-making; kept as is
      }
      if (Double.isFinite(pb) && pb != 0) {
        m.put(Metric.BOOK_YIELD, 1 / pb);
      }
      if (Double.isFinite(ps) && ps > 0) {
        m.put(Metric.SALES_YIELD, 1 / ps);
      }
    }

    AdjustedPrices p = s.prices();
    int idx = p.valid() ? p.indexAt(t, spec.staleDays()) : -1;
    if (idx >= 0) {
      for (Map.Entry<Metric, int[]> w : MOMENTUM_WINDOWS.entrySet()) {
        double r = p.returnBetween(idx, w.getValue()[0], w.getValue()[1]);
        if (Double.isFinite(r)) {
          m.put(w.getKey(), r);
        }
      }
    }
    m.keySet().retainAll(spec.metrics());

    // Gross margin and FCF margin do not apply to banks / insurers (their statement templates have
    // no such lines)
    Set<Metric> applicable = EnumSet.copyOf(spec.metrics());
    QuarterFacts latest = latestAvailable(s.quarters(), t);
    if (latest != null && latest.template().financial()) {
      applicable.removeAll(FINANCIAL_NOT_APPLICABLE);
    }

    // Exclusions are checked in a fixed order; the first one that applies is recorded
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
      detail = "截至当天 " + spec.staleDays() + " 天内没有收盘价";
    } else if (p.rawClose(idx) < spec.priceMin()) {
      ex = Exclusion.PRICE_BELOW_MIN;
      detail = "不复权收盘 " + Math.round(p.rawClose(idx) * 100) / 100.0 + " < " + spec.priceMin();
    } else if (known < spec.minQuarters()) {
      ex = Exclusion.INSUFFICIENT_HISTORY;
      detail = "已公告季报 " + known + " 季，不足 " + spec.minQuarters();
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
