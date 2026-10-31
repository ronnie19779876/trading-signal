package org.jdkxx.trader.domain.valuation;

import java.util.List;
import java.util.Map;

/**
 * Result of a sum-of-the-parts valuation. It only does arithmetic and consistency checks; it gives
 * no advice and makes no forecasts.
 *
 * @param horizonYears discount horizon (years)
 * @param discountFactor discount factor
 * @param scenarios one result per scenario (all three)
 * @param sensitivities one-factor sensitivities: move one business line, keep the rest at base
 * @param marginCheck net margin consistency check
 * @param reverse reverse calculation; null when the baseline P/E is missing
 */
public record SotpResult(
    double horizonYears,
    double discountFactor,
    Map<SotpScenario, ScenarioValue> scenarios,
    List<Sensitivity> sensitivities,
    MarginCheck marginCheck,
    Reverse reverse) {

  /**
   * Result of one scenario.
   *
   * @param segmentValues value of each business line, in input order
   * @param segmentTotal total business value
   * @param equityValue equity value = total business value + target-year net cash
   * @param targetPrice share price in the target year
   * @param presentValue share price discounted back to the base date
   * @param upsidePct discounted change relative to the current price, as a <b>percentage</b>; null
   *     when the current price is not positive
   */
  public record ScenarioValue(
      Map<String, Double> segmentValues,
      double segmentTotal,
      double equityValue,
      double targetPrice,
      double presentValue,
      Double upsidePct) {}

  /**
   * One-factor sensitivity: the discounted share-price range when this business line swings between
   * bear and bull while the other lines stay at base. In the TSLA test only Robotaxi, of the five
   * business lines, swung by an order of magnitude; the others were about ±$40 each.
   */
  public record Sensitivity(
      String segment, double lowPresentValue, double highPresentValue, double swing) {}

  /**
   * Net margin consistency check: sum of segment net income ÷ sum of segment revenue, compared with
   * the latest actual consolidated net margin. This is the check that best catches assumptions
   * where "every part is very profitable but the sum does not match the company".
   *
   * @param impliedNetMargin implied consolidated net margin of the base scenario, as a
   *     <b>fraction</b>
   * @param actualNetMargin latest actual, as a <b>fraction</b>; null when unavailable
   * @param deviationPp implied − actual, in <b>percentage points</b>; null when the actual is
   *     unavailable
   * @param ok true when the actual is unavailable (nothing to judge, so it does not block),
   *     otherwise whether the deviation is within the threshold
   */
  public record MarginCheck(
      double impliedNetMargin, Double actualNetMargin, Double deviationPp, boolean ok) {}

  /**
   * Reverse calculation: not "what is it worth" but "what is the current price already betting on".
   *
   * @param requiredTargetPrice share price needed in the target year at the required return
   * @param requiredMarketCap the corresponding target-year market cap
   * @param requiredNetIncome target-year net income needed, backed out with the baseline P/E
   * @param requiredNetIncomeCagr annualized growth from current to required net income, as a
   *     <b>fraction</b>; null when the current net income is unavailable or not positive
   */
  public record Reverse(
      double requiredTargetPrice,
      double requiredMarketCap,
      double requiredNetIncome,
      Double requiredNetIncomeCagr) {}
}
