package org.jdkxx.trader.core.marketdata.valuation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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

/**
 * Company-level basis for sum-of-the-parts valuation: current price, share count, net cash, current
 * revenue and net margin, the P/E baseline, applicability. Everything comes from the database;
 * nothing is entered by hand.
 *
 * <p><b>This only supplies numbers and makes no choice for the user.</b> The three share-count
 * bases (outstanding shares from the broker, market cap ÷ price, net income to parent ÷ diluted
 * EPS) were measured not to agree (2026-09-19 TSLA: 3.95 billion vs 3.5 billion, difference not
 * verified), so all three are given with their source labelled and the user picks.
 *
 * <p>Money values are doubles: this is an informational model whose inputs are themselves
 * estimates, not an accounting figure.
 */
public class SotpBasisService {

  /**
   * Balance sheet field ids (a field id means different things in different statement types, so it
   * must always be looked up within its statement: 8001 is total revenue in the income statement
   * but total assets in the balance sheet).
   */
  private static final long BS_CASH_AND_SHORT_TERM =
      8003L; // cash, cash equivalents and short-term investments

  private static final long BS_SHORT_TERM_BORROWING = 8058L; // short-term borrowings
  private static final long BS_SHORT_TERM_LEASE = 8060L; // short-term finance lease liabilities
  private static final long BS_LONG_TERM_BORROWING = 8069L; // long-term borrowings
  private static final long BS_LONG_TERM_LEASE = 8070L; // long-term finance lease liabilities

  private static final long INCOME_REVENUE = 8001L; // total revenue
  private static final long INCOME_NET_TO_PARENT =
      8043L; // net income attributable to parent shareholders
  private static final long MAIN_NET_MARGIN = 14005L; // net margin to parent (TTM, in percent)

  /** Look-back in years for the P/E baseline. */
  private static final int PE_LOOKBACK_YEARS = 5;

  private final InstrumentDirectory directory;
  private final DailyBarRepository bars;
  private final ValuationRepository valuations;
  private final FinancialRepository financials;
  private final IndexConstituentRepository constituents;

  public SotpBasisService(
      InstrumentDirectory directory,
      DailyBarRepository bars,
      ValuationRepository valuations,
      FinancialRepository financials,
      IndexConstituentRepository constituents) {
    this.directory = directory;
    this.bars = bars;
    this.valuations = valuations;
    this.financials = financials;
    this.constituents = constituents;
  }

  /**
   * Basis and applicability.
   *
   * @param shares share count on all three bases, all given, no choice made for the user
   * @param netCash net cash breakdown
   * @param ttmRevenue sum of revenue over the four most recent quarters; null when four quarters
   *     are not available
   * @param ttmNetIncome sum of net income to parent over the four most recent quarters; null when
   *     four quarters are not available
   * @param netMarginTtm net margin to parent (TTM) from the broker, as a <b>fraction</b>
   * @param peMedian median of the positive daily-bar P/E values over the last 5 years
   * @param applicability applicability verdict
   */
  public record Basis(
      String symbol,
      String name,
      LocalDate priceDate,
      Double price,
      Shares shares,
      NetCash netCash,
      Double ttmRevenue,
      Double ttmNetIncome,
      Double netMarginTtm,
      Double peMedian,
      Applicability applicability) {

    /**
     * Converts to the basis the calculation core needs. Target-year forecasts of share count and
     * net cash are entered by the user and are not part of this. A missing price becomes 0, which
     * the calculator treats as "no upside / no reverse result".
     */
    public SotpBasis forCalculator() {
      return new SotpBasis(price == null ? 0 : price, peMedian, ttmNetIncome, netMarginTtm);
    }
  }

  /**
   * @param outstanding outstanding shares from the broker
   * @param byMarketCap market cap ÷ current price
   * @param byDilutedEps net income to parent ÷ diluted EPS
   */
  public record Shares(Long outstanding, Double byMarketCap, Double byDilutedEps) {}

  /**
   * @param cashAndShortTerm cash, cash equivalents and short-term investments
   * @param borrowings short-term + long-term borrowings
   * @param leases short-term + long-term finance lease liabilities, <b>not counted as
   *     interest-bearing debt by default</b>; the user may choose to include them
   * @param netCash cashAndShortTerm − borrowings
   * @param periodText which period the figures come from
   */
  public record NetCash(
      Double cashAndShortTerm,
      Double borrowings,
      Double leases,
      Double netCash,
      String periodText) {}

  /**
   * @param verdict APPLICABLE / CAUTION / NOT_APPLICABLE
   * @param reasons the grounds, one reason per item, not just the conclusion
   */
  public record Applicability(String verdict, List<String> reasons) {}

  public Basis of(String symbol) {
    InstrumentRow row = directory.require(symbol);
    long id = row.id();

    BigDecimal close = bars.latestClose(id).orElse(null);
    Double price = close == null ? null : close.doubleValue();
    LocalDate priceDate = bars.latestDate(id).orElse(null);

    ValuationSnapshot snap = latestSnapshot(row, id);
    List<FinancialReport> income =
        financials.find(row.instrument(), id, FinancialStatement.INCOME, 8);
    List<FinancialReport> balance =
        financials.find(row.instrument(), id, FinancialStatement.BALANCE_SHEET, 1);
    List<FinancialReport> main =
        financials.find(row.instrument(), id, FinancialStatement.MAIN_INDEX, 1);

    Double ttmRevenue = ttm(income, INCOME_REVENUE);
    Double ttmNetIncome = ttm(income, INCOME_NET_TO_PARENT);
    Double netMargin = percentToRate(value(main, MAIN_NET_MARGIN));
    // peMedian ignores zero P/E rows (Futu reports 0 for loss periods and funds) and returns an
    // empty Optional when there is no positive value at all (SPY); see DailyBarRepository.peMedian
    Double peMedian = bars.peMedian(id, LocalDate.now().minusYears(PE_LOOKBACK_YEARS)).orElse(null);

    return new Basis(
        row.symbol(),
        row.nameCn() == null ? row.name() : row.nameCn(),
        priceDate,
        price,
        shares(snap, price, ttmNetIncome),
        netCash(balance),
        ttmRevenue,
        ttmNetIncome,
        netMargin,
        peMedian,
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
    if (snap != null
        && snap.eps() != null
        && snap.eps().doubleValue() != 0
        && ttmNetIncome != null) {
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
    Double borrowings =
        sum(value(balance, BS_SHORT_TERM_BORROWING), value(balance, BS_LONG_TERM_BORROWING));
    Double leases = sum(value(balance, BS_SHORT_TERM_LEASE), value(balance, BS_LONG_TERM_LEASE));
    Double net = cash == null ? null : cash - (borrowings == null ? 0 : borrowings);
    return new NetCash(cash, borrowings, leases, net, r.periodText());
  }

  /**
   * Applicability. <b>Whether statements "should exist" is decided by whether any were fetched, not
   * by {@code secType}</b>: the broker classifies US REITs as Trust too, and filtering by type
   * would miss 25 REITs that do have statements.
   */
  private Applicability applicability(
      InstrumentRow row, List<FinancialReport> income, Double ttmNetIncome, Double peMedian) {
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

  /**
   * GICS / ICB sector from a current constituent row (until == null); null for non-constituents.
   */
  private String sectorOf(long instrumentId) {
    return constituents.historyOf(instrumentId).stream()
        .filter(c -> c.until() == null)
        .map(ConstituentRow::sector)
        .filter(java.util.Objects::nonNull)
        .findFirst()
        .orElse(null);
  }

  /**
   * Sum over the four most recent quarters. <b>The annual report and the Q4 report share the same
   * period end</b>, so only periods whose text contains /Q are taken, and rows must not be
   * de-duplicated by period end. Note that this takes the four latest quarter rows as they are: it
   * neither drops a duplicate row of the same quarter nor checks that the four quarters are
   * consecutive.
   */
  private Double ttm(List<FinancialReport> income, long fieldId) {
    List<FinancialReport> quarters =
        income.stream()
            .filter(r -> r.periodText() != null && r.periodText().contains("/Q"))
            .sorted((a, b) -> b.periodEnd().compareTo(a.periodEnd()))
            .limit(4)
            .toList();
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
