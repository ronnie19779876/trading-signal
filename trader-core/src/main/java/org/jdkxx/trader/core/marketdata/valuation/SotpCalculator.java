package org.jdkxx.trader.core.marketdata.valuation;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jdkxx.trader.domain.valuation.SotpAssumptions;
import org.jdkxx.trader.domain.valuation.SotpBasis;
import org.jdkxx.trader.domain.valuation.SotpResult;
import org.jdkxx.trader.domain.valuation.SotpScenario;
import org.jdkxx.trader.domain.valuation.SotpSegment;

/**
 * Pure calculation for sum-of-the-parts (SOTP) valuation: no Spring, no database, no gateway; it
 * computes exactly what it is given.
 *
 * <p>The structure comes from the user: for each business line, volume × price = revenue → × net
 * margin = net income → × P/E = business value; target-year share price = (Σ business value +
 * target-year net cash) ÷ target-year shares, then discounted back to the valuation date with
 * {@code discountFactor()}.
 *
 * <p><b>This class only does arithmetic and consistency checks; it does not forecast.</b> The
 * broker only provides consolidated statements, with no segment volume / price and no consensus, so
 * every business line's assumptions have to be entered by the user. Its value is in working the
 * assumptions out clearly and pointing out self-contradictory ones (net margin cross-check,
 * one-factor sensitivity, reverse calculation).
 */
public final class SotpCalculator {

  /**
   * Flag when the implied consolidated net margin deviates from the actual one by more than this
   * many percentage points. An empirical threshold, not an industry standard.
   */
  static final double MARGIN_TOLERANCE_PP = 10.0d;

  private SotpCalculator() {}

  public static SotpResult calculate(SotpAssumptions assumptions, SotpBasis basis) {
    if (assumptions == null || basis == null) {
      throw new IllegalArgumentException("缺假设或底座数据");
    }
    double horizon = assumptions.horizonYears();
    double factor = assumptions.discountFactor();

    Map<SotpScenario, SotpResult.ScenarioValue> scenarios = new EnumMap<>(SotpScenario.class);
    for (SotpScenario s : SotpScenario.values()) {
      scenarios.put(s, value(assumptions, basis, factor, segment -> segment.on(s)));
    }
    return new SotpResult(
        horizon,
        factor,
        Map.copyOf(scenarios),
        sensitivities(assumptions, basis, factor),
        marginCheck(assumptions, basis),
        reverse(assumptions, basis, horizon));
  }

  /**
   * Computes one result for a given "which scenario each business line uses". Sensitivity mixes
   * scenarios, so this takes a selector rather than a scenario. Upside is in percent and null when
   * there is no current price.
   */
  private static SotpResult.ScenarioValue value(
      SotpAssumptions a,
      SotpBasis basis,
      double factor,
      java.util.function.Function<SotpSegment, SotpSegment.SegmentCase> pick) {
    Map<String, Double> values = new LinkedHashMap<>();
    double total = 0;
    for (SotpSegment segment : a.segments()) {
      double v = pick.apply(segment).value();
      values.put(segment.name(), v);
      total += v;
    }
    double equity = total + a.targetNetCash();
    double target = equity / a.targetShares();
    double present = target * factor;
    Double upside =
        basis.currentPrice() > 0 ? (present / basis.currentPrice() - 1.0d) * 100.0d : null;
    // Wrap a LinkedHashMap; do not use Map.copyOf: it drops insertion order, while ScenarioValue's
    // javadoc explicitly promises "in input order", and the actual order would be decided by the
    // SALT the JVM randomizes at every start (found in the 2026-09-25 full-project review)
    return new SotpResult.ScenarioValue(
        java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(values)),
        total,
        equity,
        target,
        present,
        upside);
  }

  /**
   * Moves one business line at a time (bear ↔ bull) and keeps the rest at base; sorted by swing,
   * largest first. Segments are matched by identity ({@code s == moving}), so two segments with
   * equal content still move independently.
   */
  private static List<SotpResult.Sensitivity> sensitivities(
      SotpAssumptions a, SotpBasis basis, double factor) {
    List<SotpResult.Sensitivity> out = new ArrayList<>();
    for (SotpSegment moving : a.segments()) {
      double low =
          value(
                  a,
                  basis,
                  factor,
                  s -> s == moving ? s.on(SotpScenario.BEAR) : s.on(SotpScenario.BASE))
              .presentValue();
      double high =
          value(
                  a,
                  basis,
                  factor,
                  s -> s == moving ? s.on(SotpScenario.BULL) : s.on(SotpScenario.BASE))
              .presentValue();
      out.add(new SotpResult.Sensitivity(moving.name(), low, high, high - low));
    }
    out.sort((x, y) -> Double.compare(y.swing(), x.swing()));
    return List.copyOf(out);
  }

  /**
   * Σ net income ÷ Σ revenue in the base scenario, compared with the latest actual consolidated net
   * margin. Both are fractions; the deviation is reported in percentage points. Without an actual
   * margin the check passes and reports nulls.
   */
  private static SotpResult.MarginCheck marginCheck(SotpAssumptions a, SotpBasis basis) {
    double revenue = 0;
    double netIncome = 0;
    for (SotpSegment segment : a.segments()) {
      SotpSegment.SegmentCase c = segment.on(SotpScenario.BASE);
      revenue += c.revenue();
      netIncome += c.netIncome();
    }
    double implied = revenue == 0 ? 0 : netIncome / revenue;
    Double actual = basis.actualNetMargin();
    if (actual == null) {
      return new SotpResult.MarginCheck(implied, null, null, true);
    }
    double deviationPp = (implied - actual) * 100.0d;
    return new SotpResult.MarginCheck(
        implied, actual, deviationPp, Math.abs(deviationPp) <= MARGIN_TOLERANCE_PP);
  }

  /**
   * Reverse calculation: what the current price already bets on. Grow the current price at the
   * required return for the horizon, turn it into a market cap with the target share count,
   * subtract target net cash and divide by the baseline P/E to get the net income the price
   * requires; the CAGR from current net income to it follows. Without a P/E baseline there is no
   * conclusion: hard-coding a multiple would only disguise a guess as a calculation.
   */
  private static SotpResult.Reverse reverse(SotpAssumptions a, SotpBasis basis, double horizon) {
    if (basis.baselinePe() == null || basis.baselinePe() <= 0 || basis.currentPrice() <= 0) {
      return null;
    }
    double requiredPrice = basis.currentPrice() * Math.pow(1.0d + a.discountRate(), horizon);
    double requiredCap = requiredPrice * a.targetShares();
    double requiredNetIncome = (requiredCap - a.targetNetCash()) / basis.baselinePe();
    Double cagr = null;
    Double current = basis.currentNetIncome();
    if (current != null && current > 0 && requiredNetIncome > 0 && horizon > 0) {
      cagr = Math.pow(requiredNetIncome / current, 1.0d / horizon) - 1.0d;
    }
    return new SotpResult.Reverse(requiredPrice, requiredCap, requiredNetIncome, cagr);
  }
}
