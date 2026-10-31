package org.jdkxx.trader.domain.selection;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Growth and profitability metrics (§25.4), using only the quarterly reports already announced on
 * the evaluation day. TTM = sum of the latest 4 quarters; year-over-year = compared with the 4
 * quarters before that. Growth requires the latest 8 period ends to be consecutive (70~125 days
 * apart), otherwise it is missing; profitability only requires the latest 4 to be consecutive (ROE
 * additionally needs the equity from 4 quarters earlier, i.e. 5 consecutive quarters).
 */
public final class FundamentalMetrics {

  /**
   * Minimum denominator for diluted EPS growth: when the prior 4 quarters' EPS is smaller than this
   * in absolute value, growth is meaningless
   */
  static final double EPS_BASE_MIN = 0.05;

  static final int MIN_GAP_DAYS = 70;
  static final int MAX_GAP_DAYS = 125;

  /**
   * Two quarters whose period ends are less than this many days apart are the same quarter
   * (duplicate rows, or a fiscal-year change); only the later one is kept
   */
  public static final int DUPLICATE_GAP_DAYS = 30;

  private FundamentalMetrics() {}

  /**
   * Removes duplicates of "the same quarter": of two rows whose period ends are less than {@value
   * #DUPLICATE_GAP_DAYS} days apart, only the one with the later period end is kept. Two sources (7
   * cases in the full run measured on 2026-10-12): Futu gives different period-end dates when
   * fetched at different times, so one period is stored as two rows (HD, NVDA, TDY, identical
   * values); and during a fiscal-year change two rows with different period labels and different
   * values exist (GEN, COHR), where checking against that year's filings shows the later period end
   * is right. Without this, TTM sums would double-count. {@code quarters} must be in ascending
   * period-end order.
   */
  public static List<QuarterFacts> dedupe(List<QuarterFacts> quarters) {
    List<QuarterFacts> out = new ArrayList<>(quarters.size());
    for (QuarterFacts q : quarters) {
      if (!out.isEmpty()
          && ChronoUnit.DAYS.between(out.getLast().periodEnd(), q.periodEnd())
              < DUPLICATE_GAP_DAYS) {
        out.removeLast();
      }
      out.add(q);
    }
    return out;
  }

  /** Number of quarterly reports announced as of t (used by rule 5 of §25.3). */
  public static int availableCount(List<QuarterFacts> quarters, LocalDate t) {
    int n = 0;
    for (QuarterFacts q : quarters) {
      if (q.availableOn() != null && !q.availableOn().isAfter(t)) {
        n++;
      }
    }
    return n;
  }

  /**
   * Metrics computable as of t; metrics that cannot be computed are left out. {@code quarters} must
   * be in ascending period-end order. Quarters not yet available on t are skipped; if a later
   * quarter is already available, the resulting gap is rejected by the consecutiveness check
   * (point-in-time safety).
   */
  public static Map<Metric, Double> compute(List<QuarterFacts> quarters, LocalDate t) {
    List<QuarterFacts> known = new ArrayList<>();
    for (QuarterFacts q : quarters) {
      if (q.availableOn() != null && !q.availableOn().isAfter(t)) {
        known.add(q);
      }
    }
    Map<Metric, Double> out = new EnumMap<>(Metric.class);
    int n = known.size();
    if (n < 4 || !consecutive(known, n - 4, n)) {
      return out;
    }
    List<QuarterFacts> cur = known.subList(n - 4, n);
    QuarterFacts.Template template = known.get(n - 1).template();
    Double rev = sum(cur, QuarterFacts::revenue);
    Double gross = sum(cur, QuarterFacts::grossProfit);
    Double ni = sum(cur, QuarterFacts::netIncome);
    Double fcf = sum(cur, QuarterFacts::freeCashFlow);
    if (rev != null && rev > 0) {
      if (template == QuarterFacts.Template.STANDARD && gross != null) {
        out.put(Metric.GROSS_MARGIN, gross / rev);
      }
      if (!template.financial() && fcf != null) {
        out.put(Metric.FCF_MARGIN, fcf / rev);
      }
    }
    if (n >= 5 && consecutive(known, n - 5, n)) {
      Double eqNow = known.get(n - 1).equity();
      Double eqThen = known.get(n - 5).equity();
      if (ni != null && eqNow != null && eqThen != null && eqNow > 0 && eqThen > 0) {
        out.put(Metric.ROE, ni / ((eqNow + eqThen) / 2));
      }
    }
    if (n >= 8 && consecutive(known, n - 8, n)) {
      List<QuarterFacts> prev = known.subList(n - 8, n - 4);
      Double revPrev = sum(prev, QuarterFacts::revenue);
      if (rev != null && revPrev != null && revPrev > 0) {
        out.put(Metric.REVENUE_GROWTH, rev / revPrev - 1);
      }
      Double eps = sum(cur, QuarterFacts::epsDiluted);
      Double epsPrev = sum(prev, QuarterFacts::epsDiluted);
      if (eps != null && epsPrev != null && Math.abs(epsPrev) >= EPS_BASE_MIN) {
        out.put(Metric.EPS_GROWTH, (eps - epsPrev) / Math.abs(epsPrev));
      }
    }
    return out;
  }

  /**
   * Whether neighbouring period ends within [from, to) are {@value #MIN_GAP_DAYS}~{@value
   * #MAX_GAP_DAYS} days apart. It only serves to detect a missing quarter (one missing quarter is a
   * gap of about 180 days): 12-12-12-16-week fiscal years (COST, KR, PEP, AZO, DPZ) have quarters
   * of 84 days or 112~119 days. The full run measured on 2026-10-12 found 23193 gaps of 80~99 days,
   * 62 of 110~119 days and 13 of 180 days or more; the earlier 80~100-day rule misread the 16-week
   * quarter as a break and lost growth entirely for those names.
   */
  static boolean consecutive(List<QuarterFacts> qs, int from, int to) {
    for (int i = from + 1; i < to; i++) {
      long d = ChronoUnit.DAYS.between(qs.get(i - 1).periodEnd(), qs.get(i).periodEnd());
      if (d < MIN_GAP_DAYS || d > MAX_GAP_DAYS) {
        return false;
      }
    }
    return true;
  }

  private static Double sum(
      List<QuarterFacts> qs, java.util.function.Function<QuarterFacts, Double> f) {
    double s = 0;
    for (QuarterFacts q : qs) {
      Double v = f.apply(q);
      if (v == null || !Double.isFinite(v)) {
        return null;
      }
      s += v;
    }
    return s;
  }
}
