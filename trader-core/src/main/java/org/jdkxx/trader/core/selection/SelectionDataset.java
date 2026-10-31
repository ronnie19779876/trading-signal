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

  /** Minimum share price (unadjusted, §25.3 rule 4) */
  static final double PRICE_MIN = 10;

  /** Minimum number of announced quarterly reports (§25.3 rule 5) */
  static final int MIN_QUARTERS = 8;

  /**
   * How many calendar days a price / valuation may lag t and still count as "available that day"
   * (about 5 trading days)
   */
  static final int STALE_DAYS = 7;

  /** Momentum look-backs in trading days: 3, 6 and 12 months */
  static final int[] MOMENTUM_DAYS = {63, 126, 252};

  static final String COMMON = "COMMON";
  private static final Set<Metric> FINANCIAL_NOT_APPLICABLE =
      EnumSet.of(Metric.GROSS_MARGIN, Metric.FCF_MARGIN);

  /** One instrument's daily valuations, in ascending date order. */
  record ValuationSeries(LocalDate[] dates, double[] pe, double[] pb, double[] ps) {
    static final ValuationSeries EMPTY =
        new ValuationSeries(new LocalDate[0], new double[0], new double[0], new double[0]);

    /**
     * Index of the latest row on or before t, or -1 when there is none or it is older than
     * STALE_DAYS.
     */
    int indexAt(LocalDate t) {
      int i = Arrays.binarySearch(dates, t);
      if (i < 0) {
        i = -i - 2; // binarySearch miss: -(insertion point) - 1, so this is the index just before t
      }
      return i < 0 || ChronoUnit.DAYS.between(dates[i], t) > STALE_DAYS ? -1 : i;
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

    // Gross margin and FCF margin do not apply to banks / insurers (their statement templates have
    // no such lines)
    Set<Metric> applicable = EnumSet.allOf(Metric.class);
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
