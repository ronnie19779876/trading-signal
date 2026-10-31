package org.jdkxx.trader.domain.selection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FundamentalMetricsTest {

  /**
   * 8 consecutive quarters: first 4 with revenue 100 and EPS 1 each; last 4 with revenue 110 and
   * EPS 1.2 each; gross margin 40%, FCF 15%, equity 1000 to 1200.
   */
  private static List<QuarterFacts> eight(QuarterFacts.Template template) {
    List<QuarterFacts> qs = new ArrayList<>();
    LocalDate end = LocalDate.of(2023, 3, 31);
    for (int i = 0; i < 8; i++) {
      boolean recent = i >= 4;
      double rev = recent ? 110 : 100;
      qs.add(
          new QuarterFacts(
              end,
              end.getMonthValue() == 12,
              template,
              end.plusDays(30),
              rev,
              template == QuarterFacts.Template.STANDARD ? rev * 0.4 : null,
              rev * 0.1,
              recent ? 1.2 : 1.0,
              1000.0 + i * 50,
              rev * 0.15));
      end = end.plusMonths(3).withDayOfMonth(end.plusMonths(3).lengthOfMonth());
    }
    return qs;
  }

  @Test
  void 标准模板八季全部可算() {
    List<QuarterFacts> qs = eight(QuarterFacts.Template.STANDARD);
    Map<Metric, Double> m = FundamentalMetrics.compute(qs, LocalDate.of(2025, 6, 1));

    assertThat(m.get(Metric.REVENUE_GROWTH)).isCloseTo(0.10, within(1e-9));
    assertThat(m.get(Metric.EPS_GROWTH)).isCloseTo(0.20, within(1e-9));
    assertThat(m.get(Metric.GROSS_MARGIN)).isCloseTo(0.40, within(1e-9));
    assertThat(m.get(Metric.FCF_MARGIN)).isCloseTo(0.15, within(1e-9));
    assertThat(m.get(Metric.ROE)).isCloseTo(44.0 / ((1350 + 1150) / 2.0), within(1e-9));
  }

  /**
   * Point in time: a quarter not yet announced on the evaluation day cannot be used (the last
   * quarter only becomes available 30 days later).
   */
  @Test
  void 未公告的季度不用() {
    List<QuarterFacts> qs = eight(QuarterFacts.Template.STANDARD);
    LocalDate lastAvailable = qs.get(7).availableOn();

    assertThat(FundamentalMetrics.compute(qs, lastAvailable.minusDays(1)))
        .doesNotContainKey(Metric.REVENUE_GROWTH);
    assertThat(FundamentalMetrics.availableCount(qs, lastAvailable.minusDays(1))).isEqualTo(7);
    assertThat(FundamentalMetrics.compute(qs, lastAvailable)).containsKey(Metric.REVENUE_GROWTH);
  }

  @Test
  void 银行模板没有毛利率与自由现金流利润率() {
    Map<Metric, Double> m =
        FundamentalMetrics.compute(eight(QuarterFacts.Template.BANK), LocalDate.of(2025, 6, 1));

    assertThat(m)
        .doesNotContainKeys(Metric.GROSS_MARGIN, Metric.FCF_MARGIN)
        .containsKeys(Metric.ROE, Metric.REVENUE_GROWTH);
  }

  /**
   * 12-12-12-16-week fiscal year: quarters alternate between 84 and 112 days and still count as
   * consecutive (measured on COST, KR, PEP, AZO).
   */
  @Test
  void 十六周的季度也算连续() {
    List<QuarterFacts> qs = new ArrayList<>();
    LocalDate end = LocalDate.of(2023, 2, 12);
    int[] gaps = {84, 84, 112, 84, 84, 84, 112};
    for (int i = 0; i < 8; i++) {
      qs.add(
          new QuarterFacts(
              end,
              false,
              QuarterFacts.Template.STANDARD,
              end.plusDays(30),
              100.0 + i,
              40.0,
              10.0,
              1.0 + i,
              500.0,
              15.0));
      if (i < gaps.length) {
        end = end.plusDays(gaps[i]);
      }
    }

    assertThat(FundamentalMetrics.compute(qs, LocalDate.of(2025, 6, 1)))
        .containsKeys(Metric.REVENUE_GROWTH, Metric.EPS_GROWTH);
  }

  /**
   * Of two rows with period ends less than 30 days apart only the later is kept (measured: HD with
   * one period stored twice, GEN during its fiscal-year change).
   */
  @Test
  void 期末相差不到三十天的只留较晚的那条() {
    LocalDate a = LocalDate.of(2020, 6, 29);
    QuarterFacts early =
        new QuarterFacts(
            a, false, QuarterFacts.Template.STANDARD, null, 956.0, null, null, null, null, null);
    QuarterFacts late =
        new QuarterFacts(
            a.plusDays(3),
            false,
            QuarterFacts.Template.STANDARD,
            null,
            614.0,
            null,
            null,
            null,
            null,
            null);
    QuarterFacts next =
        new QuarterFacts(
            a.plusDays(94),
            false,
            QuarterFacts.Template.STANDARD,
            null,
            626.0,
            null,
            null,
            null,
            null,
            null);

    assertThat(FundamentalMetrics.dedupe(List.of(early, late, next)))
        .extracting(QuarterFacts::revenue)
        .containsExactly(614.0, 626.0);
  }

  @Test
  void 季度不连续时成长缺() {
    List<QuarterFacts> qs = eight(QuarterFacts.Template.STANDARD);
    qs.remove(2);

    assertThat(FundamentalMetrics.compute(qs, LocalDate.of(2025, 6, 1)))
        .doesNotContainKey(Metric.REVENUE_GROWTH);
  }

  @Test
  void 前四季EPS接近零时EPS同比缺_权益为负时ROE缺() {
    List<QuarterFacts> qs = new ArrayList<>();
    for (QuarterFacts q : eight(QuarterFacts.Template.STANDARD)) {
      boolean old = q.periodEnd().isBefore(LocalDate.of(2024, 3, 1));
      qs.add(
          new QuarterFacts(
              q.periodEnd(),
              q.fiscalQ4(),
              q.template(),
              q.availableOn(),
              q.revenue(),
              q.grossProfit(),
              q.netIncome(),
              old ? 0.01 : 1.0,
              old ? q.equity() : -5.0,
              q.freeCashFlow()));
    }
    Map<Metric, Double> m = FundamentalMetrics.compute(qs, LocalDate.of(2025, 6, 1));

    assertThat(m)
        .doesNotContainKeys(Metric.EPS_GROWTH, Metric.ROE)
        .containsKey(Metric.REVENUE_GROWTH);
  }
}
