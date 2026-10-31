package org.jdkxx.trader.domain.holding;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.jdkxx.trader.domain.signal.Indicators;
import org.jdkxx.trader.domain.signal.SignalBar;

/**
 * Daily trend state and position-reduction review conditions (3.5.0, ARCHITECTURE §29). Pure
 * function: the input is daily bars on the structural basis (continuous across splits), and each
 * day uses only that day's and earlier data.
 *
 * <p><b>This is not a buy/sell signal.</b> In the phase-4 backtest, entry criteria containing
 * "close above the 200-day average and the average rising" showed no significant difference from
 * "entering the same instrument on any day of the same year" (§18.6), so the moving average cannot
 * be shown to have timing value. This class only checks, one by one, the discipline conditions the
 * user set; whether they carry any warning value is answered by the historical validation in §29.
 */
public final class TrendReview {

  /** Trend state. */
  public enum State {
    /**
     * Close &gt; long average and the long average rising (= the entry signal's trend gate passes),
     * and close &gt; short average
     */
    UP_STRONG("上升（强）"),
    /** Trend gate passes, but close &lt;= short average */
    UP_WEAK("上升（走弱）"),
    /** Close &gt; long average, but the long average is not rising */
    ABOVE_FLAT("均线走平"),
    /** Close &lt;= long average, for fewer consecutive days than needed for confirmation */
    BELOW("跌破（待确认）"),
    /** Close &lt;= long average for the confirmation number of consecutive days */
    BROKEN("破位"),
    /** Not enough samples */
    UNAVAILABLE("样本不足");

    private final String label;

    State(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * Position-reduction review conditions (price-based; earnings risk over budget is computed
   * separately by the panel).
   */
  public enum Trigger {
    /** Close &lt;= long average for the confirmation number of consecutive days */
    BREAK_CONFIRMED,
    /** Short average &lt; long average (the state after a death cross) */
    DEATH_CROSS,
    /** Close &lt;= chandelier line (highest high of the latest N bars − k × ATR) */
    CHANDELIER_BREACH
  }

  /**
   * Verdict for one day. Moving averages and the chandelier are NaN while there are too few
   * samples.
   *
   * @param belowDays number of consecutive closes &lt;= the long average up to this day (0 when
   *     above)
   * @param active review conditions holding on this day
   * @param edges review conditions that <b>just</b> started holding on this day (not holding the
   *     day before); the historical validation only counts these, so one decline is not counted
   *     repeatedly
   */
  public record Day(
      LocalDate date,
      double close,
      double high,
      double smaShort,
      double smaLong,
      double smaLongPrior,
      State state,
      int belowDays,
      double atr,
      double chandelier,
      Set<Trigger> active,
      Set<Trigger> edges) {}

  private TrendReview() {}

  public static List<Day> evaluate(List<SignalBar> bars, HoldingRules rules) {
    int n = bars.size();
    double[] close = bars.stream().mapToDouble(SignalBar::close).toArray();
    double[] high = bars.stream().mapToDouble(SignalBar::high).toArray();
    double[] low = bars.stream().mapToDouble(SignalBar::low).toArray();
    double[] smaS = Indicators.sma(close, rules.smaShort());
    double[] smaL = Indicators.sma(close, rules.smaLong());
    double[] atr = Indicators.wilderAtr(high, low, close, rules.atrPeriod());
    List<Day> out = new ArrayList<>(n);
    int below = 0;
    Set<Trigger> previous = EnumSet.noneOf(Trigger.class);
    for (int t = 0; t < n; t++) {
      double prior = t >= rules.slopeLookback() ? smaL[t - rules.slopeLookback()] : Double.NaN;
      boolean ready = !Double.isNaN(smaL[t]) && !Double.isNaN(prior) && !Double.isNaN(smaS[t]);
      if (!Double.isNaN(smaL[t])) {
        below = close[t] <= smaL[t] ? below + 1 : 0;
      }
      double hh = Double.NEGATIVE_INFINITY;
      for (int i = Math.max(0, t - rules.chandelierPeriod() + 1); i <= t; i++) {
        hh = Math.max(hh, high[i]);
      }
      double chandelier =
          t + 1 >= rules.chandelierPeriod() && !Double.isNaN(atr[t])
              ? hh - rules.chandelierAtrMultiple() * atr[t]
              : Double.NaN;

      State state;
      Set<Trigger> active = EnumSet.noneOf(Trigger.class);
      if (!ready) {
        state = State.UNAVAILABLE;
      } else {
        boolean above = close[t] > smaL[t];
        boolean rising = smaL[t] > prior;
        if (above && rising) {
          state = close[t] > smaS[t] ? State.UP_STRONG : State.UP_WEAK;
        } else if (above) {
          state = State.ABOVE_FLAT;
        } else {
          state = below >= rules.breakConfirmDays() ? State.BROKEN : State.BELOW;
        }
        if (state == State.BROKEN) {
          active.add(Trigger.BREAK_CONFIRMED);
        }
        if (rules.deathCross() && smaS[t] < smaL[t]) {
          active.add(Trigger.DEATH_CROSS);
        }
        if (!Double.isNaN(chandelier) && close[t] <= chandelier) {
          active.add(Trigger.CHANDELIER_BREACH);
        }
      }
      Set<Trigger> edges = EnumSet.noneOf(Trigger.class);
      for (Trigger x : active) {
        // A condition already holding on the first day that can be evaluated is not "just started":
        // we cannot know how long it had been holding before
        if (!previous.contains(x) && t > 0 && out.get(t - 1).state() != State.UNAVAILABLE) {
          edges.add(x);
        }
      }
      out.add(
          new Day(
              bars.get(t).date(),
              close[t],
              high[t],
              smaS[t],
              smaL[t],
              prior,
              state,
              below,
              atr[t],
              chandelier,
              Set.copyOf(active),
              Set.copyOf(edges)));
      previous = active;
    }
    return out;
  }
}
