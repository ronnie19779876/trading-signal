package org.jdkxx.trader.domain.valuation;

import java.util.EnumMap;
import java.util.Map;

/**
 * Assumptions of one business line: volume × price = revenue, × net margin = net income, × P/E =
 * business value.
 *
 * <p><b>Net margin means net margin</b> (after allocated R&amp;D, overhead and tax), neither gross
 * margin nor operating margin: the segments' net income must add up to the company's consolidated
 * net income, and {@code SotpCalculator} checks against the actual consolidated net margin.
 *
 * <p>{@code scopeNote} is mandatory and guards against double counting: counting the same cars in
 * vehicle-sales revenue and again in Robotaxi and FSD is the easiest mistake to make with this
 * model (TSLA's FSD purchase revenue is already booked in vehicle-sales revenue). If the scope
 * cannot be written down, it has not been thought through.
 *
 * @param name business line name
 * @param scopeNote scope note, mandatory
 * @param cases one set of assumptions per scenario; none may be missing
 */
public record SotpSegment(String name, String scopeNote, Map<SotpScenario, SegmentCase> cases) {

  public SotpSegment {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("业务线名称不能为空");
    }
    if (scopeNote == null || scopeNote.isBlank()) {
      throw new IllegalArgumentException("业务线「" + name + "」缺口径备注：写清算什么、不算什么，否则挡不住重复计算");
    }
    if (cases == null) {
      throw new IllegalArgumentException("业务线「" + name + "」缺情景假设");
    }
    Map<SotpScenario, SegmentCase> copy = new EnumMap<>(SotpScenario.class);
    copy.putAll(cases);
    for (SotpScenario s : SotpScenario.values()) {
      if (copy.get(s) == null) {
        throw new IllegalArgumentException("业务线「" + name + "」缺 " + s + " 情景：三个情景必须都给，不接受单点估值");
      }
    }
    cases = Map.copyOf(copy);
  }

  public SegmentCase on(SotpScenario scenario) {
    return cases.get(scenario);
  }

  /**
   * Assumptions under one scenario.
   *
   * @param volume volume (unit chosen by the user: vehicles, subscriptions, GWh ..., as long as it
   *     matches the price)
   * @param price unit price (same basis as the volume, so the product is the business's annual
   *     revenue)
   * @param netMargin net margin as a <b>fraction</b> (pass 0.07 for 7%, not 7)
   * @param pe P/E. Reference ranges: autos 10~20, energy 15~30, software 20~40; the target-year P/E
   *     depends on the growth after the target year
   */
  public record SegmentCase(double volume, double price, double netMargin, double pe) {

    public SegmentCase {
      if (volume < 0 || price < 0 || pe < 0) {
        throw new IllegalArgumentException("量、价、本益比不能为负");
      }
      if (netMargin > 1.0 || netMargin < -1.0) {
        throw new IllegalArgumentException("净利率要传小数（7% 传 0.07），收到 " + netMargin);
      }
    }

    public double revenue() {
      return volume * price;
    }

    public double netIncome() {
      return revenue() * netMargin;
    }

    public double value() {
      return netIncome() * pe;
    }
  }
}
