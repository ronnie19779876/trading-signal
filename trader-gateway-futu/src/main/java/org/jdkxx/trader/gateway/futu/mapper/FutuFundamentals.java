package org.jdkxx.trader.gateway.futu.mapper;

import com.futu.openapi.pb.QotCommon;
import com.futu.openapi.pb.QotGetCompanyProfile;
import com.futu.openapi.pb.QotGetFinancialsStatements;
import com.futu.openapi.pb.QotGetSecuritySnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jdkxx.trader.domain.CompanyProfile;
import org.jdkxx.trader.domain.FinancialReport;
import org.jdkxx.trader.domain.FinancialStatement;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.ValuationSnapshot;

/**
 * Futu fundamentals → domain.
 *
 * <p>Single stocks use equityExData (P/E, P/B, EPS, ...), ETFs use trustExData (NAV, premium,
 * assets under management). The two do not share fields: an ETF's AUM goes into netAsset, its units
 * into outstandingShares, its dividend yield into dividendYieldTtm, and NAV and premium into
 * navPerShare / premium.
 *
 * <p><b>Value semantics follow measurement</b> (run on 2026-09-09 against 400 index constituents
 * plus several loss-making stocks):
 *
 * <ul>
 *   <li>the valuation fields are required in the proto, so {@code has*()} is always true and null
 *       checks are meaningless;
 *   <li>the P/E and P/B of loss-making stocks are <b>negative</b>, not 0 or missing (measured INTC
 *       -49.99, LCID -0.34); negative values must be kept as they are;
 *   <li>none of the 400 had a P/E or market cap of 0, so 0 is not a "no data" sentinel and values
 *       are taken directly;
 *   <li>the one exception is the NAV of Trust securities: Futu gives no NAV for the 25 REITs in the
 *       S&amp;P 500 (it returns 0), only real ETFs (SPY) have one. That 0 becomes null, and since
 *       the premium is derived from the NAV it is dropped as well when the NAV is missing;
 *   <li>a dividend of 0 genuinely means "pays no dividend" (86 of the 400), so 0 is kept and not
 *       turned into null.
 * </ul>
 */
public final class FutuFundamentals {

  private FutuFundamentals() {}

  public static List<ValuationSnapshot> toSnapshots(List<QotGetSecuritySnapshot.Snapshot> list) {
    List<ValuationSnapshot> out = new ArrayList<>(list.size());
    for (QotGetSecuritySnapshot.Snapshot s : list) {
      QotGetSecuritySnapshot.SnapshotBasicData b = s.getBasic();
      Instrument instrument =
          new Instrument(
              FutuStatics.market(b.getSecurity().getMarket()), b.getSecurity().getCode());
      Instant asOf =
          b.getUpdateTimestamp() > 0
              ? Instant.ofEpochSecond((long) b.getUpdateTimestamp())
              : Instant.now();
      BigDecimal turnoverRate = b.hasTurnoverRate() ? dec(b.getTurnoverRate()) : null;
      BigDecimal lastPrice = b.getCurPrice() > 0 ? dec(b.getCurPrice()) : null;

      if (s.hasEquityExData()) {
        QotGetSecuritySnapshot.EquitySnapshotExData e = s.getEquityExData();
        out.add(
            new ValuationSnapshot(
                instrument,
                asOf,
                b.getIsSuspend(),
                dec(e.getIssuedMarketVal()),
                dec(e.getOutstandingMarketVal()),
                e.getIssuedShares(),
                e.getOutstandingShares(),
                dec(e.getPeRate()),
                dec(e.getPeTTMRate()),
                dec(e.getPbRate()),
                dec(e.getEarningsPershare()),
                dec(e.getNetAssetPershare()),
                dec(e.getNetAsset()),
                dec(e.getNetProfit()),
                dec(e.getDividendTTM()),
                dec(e.getDividendRatioTTM()),
                turnoverRate,
                null,
                null,
                lastPrice));
      } else if (s.hasTrustExData()) {
        QotGetSecuritySnapshot.TrustSnapshotExData t = s.getTrustExData();
        boolean hasNav =
            t.getNetAssetValue()
                // Futu gives REITs no NAV and uses 0 as a placeholder; only real ETFs have one
                != 0;
        out.add(
            new ValuationSnapshot(
                instrument,
                asOf,
                b.getIsSuspend(),
                null,
                null,
                null,
                t.getOutstandingUnits(),
                null,
                null,
                null,
                null,
                null,
                dec(t.getAum()),
                null,
                null,
                dec(t.getDividendYield()),
                turnoverRate,
                hasNav ? dec(t.getNetAssetValue()) : null,
                hasNav ? dec(t.getPremium()) : null,
                lastPrice));
      } else {
        // Indices, warrants, options etc. have no valuation semantics: record only the identity and
        // the suspension flag and let the upper layer decide whether to persist it
        out.add(
            new ValuationSnapshot(
                instrument,
                asOf,
                b.getIsSuspend(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                turnoverRate,
                null,
                null,
                lastPrice));
      }
    }
    return out;
  }

  /**
   * Field ids mean different things in different statement types (8001 is total revenue in the
   * income statement but total assets in the balance sheet), so items are identified by (statement,
   * field id) downstream and the display names come from this reply's own structure list.
   */
  public static List<FinancialReport> toReports(
      Instrument instrument, FinancialStatement statement, QotGetFinancialsStatements.S2C s2c) {
    Map<Long, String> names = new LinkedHashMap<>();
    for (QotGetFinancialsStatements.FinancialFieldInfo f : s2c.getStructureListList()) {
      names.put(f.getFieldId(), f.getDisplayName());
    }
    List<FinancialReport> out = new ArrayList<>();
    for (QotGetFinancialsStatements.FinancialReport r : s2c.getReportListList()) {
      List<FinancialReport.Item> items = new ArrayList<>(r.getItemListCount());
      for (QotGetFinancialsStatements.FinancialItem i : r.getItemListList()) {
        items.add(
            new FinancialReport.Item(
                i.getFieldId(),
                names.get(i.getFieldId()),
                dec(i.hasData(), i.getData()),
                dec(i.hasYoy(), i.getYoy()),
                dec(i.hasQoq(), i.getQoq())));
      }
      out.add(
          new FinancialReport(
              instrument,
              statement,
              periodEnd(r),
              r.getFiscalYear(),
              emptyToNull(r.getPeriodText()),
              emptyToNull(r.getCurrencyCode()),
              emptyToNull(r.getAccountingStandards()),
              emptyToNull(r.getAuditorReport()),
              List.copyOf(items)));
    }
    return out;
  }

  public static CompanyProfile toProfile(Instrument instrument, QotGetCompanyProfile.S2C s2c) {
    Map<String, String> fields = new LinkedHashMap<>();
    for (QotGetCompanyProfile.CompanyLabItem i : s2c.getItemListList()) {
      if (i.hasName() && i.hasValue()) {
        fields.put(i.getName(), i.getValue());
      }
    }
    return new CompanyProfile(instrument, Map.copyOf(fields));
  }

  /** Domain enum → Futu statement type. */
  public static int statementType(FinancialStatement s) {
    return switch (s) {
      case INCOME -> QotCommon.FinancialStatementsType.FinancialStatementsType_Income_VALUE;
      case BALANCE_SHEET ->
          QotCommon.FinancialStatementsType.FinancialStatementsType_BalanceSheet_VALUE;
      case CASH_FLOW -> QotCommon.FinancialStatementsType.FinancialStatementsType_CashFlow_VALUE;
      case MAIN_INDEX -> QotCommon.FinancialStatementsType.FinancialStatementsType_MainIndex_VALUE;
    };
  }

  static LocalDate periodEnd(QotGetFinancialsStatements.FinancialReport r) {
    String s = r.getDateTimeStr();
    if (s != null && s.length() >= 10) {
      try {
        return LocalDate.parse(s.substring(0, 10));
      } catch (RuntimeException ignored) {
        // Fall back to the timestamp
      }
    }
    return r.getDateTime() > 0
        ? LocalDate.ofInstant(Instant.ofEpochSecond(r.getDateTime()), java.time.ZoneOffset.UTC)
        : null;
  }

  private static BigDecimal dec(double v) {
    return BigDecimal.valueOf(v);
  }

  private static BigDecimal dec(boolean present, double v) {
    return present ? BigDecimal.valueOf(v) : null;
  }

  private static String emptyToNull(String s) {
    return s == null || s.isEmpty() ? null : s;
  }
}
