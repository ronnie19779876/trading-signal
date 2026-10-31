package org.jdkxx.trader.core.marketdata.valuation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.jdkxx.trader.core.marketdata.InstrumentDirectory;
import org.jdkxx.trader.domain.FinancialReport;
import org.jdkxx.trader.domain.FinancialStatement;
import org.jdkxx.trader.domain.IndexCode;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.SecurityType;
import org.jdkxx.trader.storage.marketdata.ConstituentRow;
import org.jdkxx.trader.storage.marketdata.DailyBarRepository;
import org.jdkxx.trader.storage.marketdata.FinancialRepository;
import org.jdkxx.trader.storage.marketdata.IndexConstituentRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.jdkxx.trader.storage.marketdata.ValuationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Basis fetching. The numbers come from TSLA's real 2026/Q2 statements in the production database
 * on 2026-09-22 (cash and short-term investments 43.524 billion, short-term borrowings 1.340
 * billion, long-term borrowings 7.721 billion → net cash 34.463 billion, matching the 34.5 billion
 * computed by hand on 09-19).
 */
class SotpBasisServiceTest {

  private final InstrumentDirectory directory = mock(InstrumentDirectory.class);
  private final DailyBarRepository bars = mock(DailyBarRepository.class);
  private final ValuationRepository valuations = mock(ValuationRepository.class);
  private final FinancialRepository financials = mock(FinancialRepository.class);
  private final IndexConstituentRepository constituents = mock(IndexConstituentRepository.class);

  private final SotpBasisService service =
      new SotpBasisService(directory, bars, valuations, financials, constituents);

  private static final InstrumentRow TSLA =
      new InstrumentRow(
          438L,
          Market.US,
          "TSLA",
          "Tesla",
          "特斯拉",
          SecurityType.STOCK,
          1,
          LocalDate.of(2010, 6, 29),
          false,
          "NASDAQ",
          1L,
          "RESOLVED");

  @BeforeEach
  void setUp() {
    when(directory.require("TSLA")).thenReturn(TSLA);
    when(bars.latestClose(438L)).thenReturn(Optional.of(new BigDecimal("364.18")));
    when(bars.latestDate(438L)).thenReturn(Optional.of(LocalDate.of(2026, 9, 18)));
    when(bars.peMedian(anyLong(), any())).thenReturn(Optional.of(72.5d));
    when(valuations.find(any(), anyLong(), any(), any())).thenReturn(List.of());
    when(constituents.historyOf(438L)).thenReturn(List.of());
    when(financials.find(any(), anyLong(), any(FinancialStatement.class), anyInt()))
        .thenReturn(List.of());
    when(financials.find(any(), anyLong(), eq(FinancialStatement.BALANCE_SHEET), anyInt()))
        .thenReturn(List.of(balanceSheet()));
    when(financials.find(any(), anyLong(), eq(FinancialStatement.INCOME), anyInt()))
        .thenReturn(fourQuartersAndAnnual());
    when(financials.find(any(), anyLong(), eq(FinancialStatement.MAIN_INDEX), anyInt()))
        .thenReturn(List.of(mainIndex()));
  }

  @Test
  void 净现金是现金及短投减有息借款_融资租赁单列不计入() {
    SotpBasisService.NetCash net = service.of("TSLA").netCash();
    assertThat(net.cashAndShortTerm()).isEqualTo(43_524_000_000d);
    assertThat(net.borrowings()).isEqualTo(1_340_000_000d + 7_721_000_000d);
    assertThat(net.leases()).isEqualTo(1_100_000_000d + 5_919_000_000d);
    assertThat(net.netCash()).isEqualTo(34_463_000_000d); // 34.463 billion
    assertThat(net.periodText()).isEqualTo("2026/Q2");
  }

  @Test
  void TTM只取四个季报_年报与四季报期末同一天也不会被算两遍() {
    SotpBasisService.Basis b = service.of("TSLA");
    // Four quarterly reports with 10 billion revenue / 1 billion net income each; the annual report
    // mixed in (same period end as Q4) must not be counted
    assertThat(b.ttmRevenue()).isEqualTo(40_000_000_000d);
    assertThat(b.ttmNetIncome()).isEqualTo(4_000_000_000d);
  }

  /**
   * Futu can store one quarter as two rows with period ends a few days apart (HD, NVDA, TDY in the
   * dev database). Before 3.5.2 the TTM summed both and so spanned only 3 real quarters.
   */
  @Test
  void TTM去掉同一季的重复行() {
    when(financials.find(any(), anyLong(), eq(FinancialStatement.INCOME), anyInt()))
        .thenReturn(
            List.of(
                quarter(LocalDate.of(2026, 6, 29), "2026/Q2", 13e9, 1.3e9),
                quarter(LocalDate.of(2026, 6, 25), "2026/Q2", 13e9, 1.3e9),
                quarter(LocalDate.of(2026, 3, 31), "2026/Q1", 11e9, 1.1e9),
                quarter(LocalDate.of(2025, 12, 31), "2025/Q4", 10e9, 1.0e9),
                quarter(LocalDate.of(2025, 9, 30), "2025/Q3", 9e9, 0.9e9)));

    SotpBasisService.Basis b = service.of("TSLA");

    // 13 + 11 + 10 + 9; counting the duplicate Q2 row would give 13 + 13 + 11 + 10 = 47
    assertThat(b.ttmRevenue()).isEqualTo(43e9);
    assertThat(b.ttmNetIncome()).isEqualTo(4.3e9);
  }

  /**
   * A missing quarter makes "the latest 4 rows" span more than a year (measured 2026-10-31 in the
   * dev database: FERG, HONA, PSKY, SPCX); such a sum is not a trailing twelve months.
   */
  @Test
  void TTM缺季时不给值() {
    when(financials.find(any(), anyLong(), eq(FinancialStatement.INCOME), anyInt()))
        .thenReturn(
            List.of(
                quarter(LocalDate.of(2026, 6, 29), "2026/Q2", 10e9, 1e9),
                quarter(LocalDate.of(2026, 3, 30), "2026/Q1", 10e9, 1e9),
                quarter(LocalDate.of(2025, 12, 30), "2025/Q4", 10e9, 1e9),
                quarter(LocalDate.of(2025, 4, 29), "2025/Q1", 10e9, 1e9)));

    SotpBasisService.Basis b = service.of("TSLA");

    assertThat(b.ttmRevenue()).isNull();
    assertThat(b.ttmNetIncome()).isNull();
    assertThat(b.applicability().verdict()).isEqualTo("CAUTION");
    assertThat(b.applicability().reasons()).anyMatch(r -> r.contains("取不到 TTM"));
  }

  /**
   * 12-12-12-16-week fiscal years (COST, KR, PEP) have 84-day and 112-day quarters: still a TTM.
   */
  @Test
  void TTM认十六周的季度() {
    when(financials.find(any(), anyLong(), eq(FinancialStatement.INCOME), anyInt()))
        .thenReturn(
            List.of(
                quarter(LocalDate.of(2026, 8, 30), "2026/Q4", 10e9, 1e9),
                quarter(LocalDate.of(2026, 5, 10), "2026/Q3", 10e9, 1e9),
                quarter(LocalDate.of(2026, 2, 15), "2026/Q2", 10e9, 1e9),
                quarter(LocalDate.of(2025, 11, 23), "2026/Q1", 10e9, 1e9)));

    assertThat(service.of("TSLA").ttmRevenue()).isEqualTo(40e9);
  }

  @Test
  void 券商给的净利率是百分数_换成小数() {
    assertThat(service.of("TSLA").netMarginTtm()).isEqualTo(0.036731d);
  }

  @Test
  void 三种口径的股数都给_不替使用者选() {
    when(valuations.find(any(), anyLong(), any(), any())).thenReturn(List.of(snapshot()));
    SotpBasisService.Shares shares = service.of("TSLA").shares();
    assertThat(shares.outstanding()).isEqualTo(3_500_000_000L); // from the broker
    assertThat(shares.byMarketCap())
        .isCloseTo(3_954_088_637d, within(1d)); // 1.44 trillion ÷ 364.18
    assertThat(shares.byDilutedEps())
        .isCloseTo(3_703_703_703d, within(1d)); // 4 billion net income ÷ 1.08
  }

  @Test
  void 取不到财报判不适用_基金与指数没有分部利润可拆() {
    when(financials.find(any(), anyLong(), eq(FinancialStatement.INCOME), anyInt()))
        .thenReturn(List.of());
    SotpBasisService.Applicability a = service.of("TSLA").applicability();
    assertThat(a.verdict()).isEqualTo("NOT_APPLICABLE");
    assertThat(a.reasons()).anyMatch(r -> r.contains("取不到财报"));
  }

  @Test
  void REITs按行业提示而不是按证券类型_富途把REITs也归为Trust() {
    InstrumentRow reit =
        new InstrumentRow(
            700L,
            Market.US,
            "O",
            "Realty Income",
            "房地产收益",
            SecurityType.ETF,
            1,
            LocalDate.of(1994, 10, 18),
            false,
            "NYSE",
            2L,
            "RESOLVED");
    when(directory.require("O")).thenReturn(reit);
    when(bars.latestClose(700L)).thenReturn(Optional.of(new BigDecimal("60")));
    when(bars.latestDate(700L)).thenReturn(Optional.of(LocalDate.of(2026, 9, 18)));
    when(constituents.historyOf(700L))
        .thenReturn(
            List.of(
                new ConstituentRow(
                    IndexCode.SP500,
                    700L,
                    "Real Estate",
                    "Retail REITs",
                    "GICS",
                    LocalDate.of(2015, 4, 7),
                    null,
                    "WIKI")));

    SotpBasisService.Applicability a = service.of("O").applicability();
    // The broker labels the security type ETF, but statements were fetched → must not be judged not
    // applicable; only the sector-based caution applies
    assertThat(a.verdict()).isEqualTo("CAUTION");
    assertThat(a.reasons()).anyMatch(r -> r.contains("FFO"));
    assertThat(a.reasons()).noneMatch(r -> r.contains("取不到财报"));
  }

  @Test
  void 亏损与缺本益比基准都要说出原因() {
    when(bars.peMedian(anyLong(), any())).thenReturn(Optional.empty());
    when(financials.find(any(), anyLong(), eq(FinancialStatement.INCOME), anyInt()))
        .thenReturn(lossQuarters());
    SotpBasisService.Applicability a = service.of("TSLA").applicability();
    assertThat(a.verdict()).isEqualTo("CAUTION");
    assertThat(a.reasons()).anyMatch(r -> r.contains("本益比没有意义"));
    assertThat(a.reasons()).anyMatch(r -> r.contains("取不到本益比基准"));
  }

  private static FinancialReport balanceSheet() {
    return report(
        FinancialStatement.BALANCE_SHEET,
        LocalDate.of(2026, 6, 29),
        "2026/Q2",
        List.of(
            item(8003L, "现金及现金等价物和短期投资", 43_524_000_000d),
            item(8058L, "短期借款", 1_340_000_000d),
            item(8060L, "短期融资租赁负债", 1_100_000_000d),
            item(8069L, "长期借款", 7_721_000_000d),
            item(8070L, "长期融资租赁负债", 5_919_000_000d)));
  }

  /**
   * Four quarterly reports + one annual report with the same period end as Q4: the annual report
   * must not get into the TTM.
   */
  private static List<FinancialReport> fourQuartersAndAnnual() {
    return List.of(
        quarter(LocalDate.of(2026, 6, 29), "2026/Q2", 10_000_000_000d, 1_000_000_000d),
        quarter(LocalDate.of(2026, 3, 31), "2026/Q1", 10_000_000_000d, 1_000_000_000d),
        quarter(LocalDate.of(2025, 12, 31), "2025/Q4", 10_000_000_000d, 1_000_000_000d),
        report(
            FinancialStatement.INCOME,
            LocalDate.of(2025, 12, 31),
            "2025/FY",
            List.of(
                item(8001L, "总收入", 94_800_000_000d), item(8043L, "归属于母公司股东净利润", 3_800_000_000d))),
        quarter(LocalDate.of(2025, 9, 30), "2025/Q3", 10_000_000_000d, 1_000_000_000d));
  }

  private static List<FinancialReport> lossQuarters() {
    return List.of(
        quarter(LocalDate.of(2026, 6, 29), "2026/Q2", 10_000_000_000d, -500_000_000d),
        quarter(LocalDate.of(2026, 3, 31), "2026/Q1", 10_000_000_000d, -500_000_000d),
        quarter(LocalDate.of(2025, 12, 31), "2025/Q4", 10_000_000_000d, -500_000_000d),
        quarter(LocalDate.of(2025, 9, 30), "2025/Q3", 10_000_000_000d, -500_000_000d));
  }

  private static FinancialReport mainIndex() {
    return report(
        FinancialStatement.MAIN_INDEX,
        LocalDate.of(2026, 6, 29),
        "2026/Q2",
        List.of(item(14005L, "归母净利率", 3.6731d)));
  }

  private static FinancialReport quarter(
      LocalDate end, String text, double revenue, double netIncome) {
    return report(
        FinancialStatement.INCOME,
        end,
        text,
        List.of(item(8001L, "总收入", revenue), item(8043L, "归属于母公司股东净利润", netIncome)));
  }

  private static FinancialReport report(
      FinancialStatement statement, LocalDate end, String text, List<FinancialReport.Item> items) {
    return new FinancialReport(
        new Instrument(Market.US, "TSLA"),
        statement,
        end,
        end.getYear(),
        text,
        "USD",
        "US-GAAP",
        null,
        items);
  }

  private static FinancialReport.Item item(long fieldId, String name, double value) {
    return new FinancialReport.Item(fieldId, name, BigDecimal.valueOf(value), null, null);
  }

  private static org.jdkxx.trader.domain.ValuationSnapshot snapshot() {
    return new org.jdkxx.trader.domain.ValuationSnapshot(
        new Instrument(Market.US, "TSLA"),
        java.time.Instant.parse("2026-09-18T20:00:00Z"),
        false,
        new BigDecimal("1440000000000"),
        null,
        3_600_000_000L,
        3_500_000_000L,
        new BigDecimal("337"),
        new BigDecimal("337"),
        new BigDecimal("15"),
        new BigDecimal("1.08"),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private static org.assertj.core.data.Offset<Double> within(double v) {
    return org.assertj.core.data.Offset.offset(v);
  }
}
