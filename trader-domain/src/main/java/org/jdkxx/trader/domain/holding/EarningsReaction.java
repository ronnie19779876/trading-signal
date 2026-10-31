package org.jdkxx.trader.domain.holding;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.jdkxx.trader.domain.selection.EarningsEvent;
import org.jdkxx.trader.domain.selection.EarningsTiming;
import org.jdkxx.trader.domain.signal.SignalBar;

/**
 * Price reaction after earnings (3.5.0, ARCHITECTURE §29). Pure function; inputs are daily bars on
 * the structural basis (continuous across splits, so ratios are unaffected by splits) and the
 * broker's announcement dates.
 *
 * <p>The reaction day depends on the announcement time of day:
 *
 * <ul>
 *   <li>pre-market / during market: the announcement day relative to the previous trading day's
 *       close;
 *   <li>after market: the first trading day after the announcement relative to the announcement
 *       day's close;
 *   <li>unknown: computed both ways, pre-market style (that day vs the previous close) and
 *       after-market style (next day vs the announcement day's close), and <b>the day with the
 *       larger move is taken</b>. At first a two-day window was used (next day's close vs the close
 *       before the announcement); checking GOOG on the dev instance showed it dilutes the move: for
 *       2022/Q3 the announcement day first rose 1.9%, the two days combined were only −7.91%, while
 *       the next day alone was −9.63%, so the two-day window is not conservative. Taking the larger
 *       day is conservative in magnitude but may pick up unrelated news of the same period (GOOG
 *       2022/Q4: it took the announcement day's +7.27%, while the real reaction was the next day's
 *       −3.29%).
 * </ul>
 *
 * Events whose reaction day has no bar yet (future announcements, or ones just published before the
 * close) are left out.
 */
public final class EarningsReaction {

  /** Threshold for a big move: |daily change| &gt;= 5% */
  public static final double BIG_MOVE = 0.05;

  /**
   * @param gap open relative to the base close
   * @param move close relative to the base close
   */
  public record Sample(
      String period,
      LocalDate pubDate,
      EarningsTiming timing,
      LocalDate reactionDate,
      Double gap,
      double move) {}

  /**
   * @param maxDrop largest drop as a positive number (0 if it never fell)
   * @param worstGap worst opening gap (null when no gap is available)
   */
  public record Stats(
      int samples,
      double meanAbsMove,
      double medianAbsMove,
      double maxDrop,
      double maxRise,
      int bigMoves,
      int ups,
      int downs,
      Double worstGap) {}

  private EarningsReaction() {}

  /**
   * Whether the evaluation day's close already reflects this announcement: an earlier day, or the
   * evaluation day itself pre-market / during market (the same rule as the model input's
   * calendarHint). After-market or unknown timing on the evaluation day counts as not yet
   * reflected.
   */
  public static boolean reflected(EarningsEvent e, LocalDate asOf) {
    if (e.pubDate().isBefore(asOf)) {
      return true;
    }
    return e.pubDate().equals(asOf)
        && (e.timing() == EarningsTiming.PRE_MARKET || e.timing() == EarningsTiming.DURING_MARKET);
  }

  public static List<Sample> samples(List<SignalBar> bars, List<EarningsEvent> events) {
    List<Sample> out = new ArrayList<>();
    for (EarningsEvent e : events) {
      Sample s = sample(bars, e);
      if (s != null) {
        out.add(s);
      }
    }
    return out;
  }

  static Sample sample(List<SignalBar> bars, EarningsEvent e) {
    int before = -1; // last bar before the announcement day
    int on = -1; // the announcement day itself
    int after = -1; // first bar after the announcement day
    for (int i = 0; i < bars.size(); i++) {
      LocalDate d = bars.get(i).date();
      if (d.isBefore(e.pubDate())) {
        before = i;
      } else if (d.equals(e.pubDate())) {
        on = i;
      } else {
        after = i;
        break;
      }
    }
    switch (e.timing()) {
      case PRE_MARKET, DURING_MARKET -> {
        // The announcement day is not a trading day (Futu gives the effective trading day, so this
        // is rare): fall through to the next trading day
        int react = on >= 0 ? on : after;
        if (react < 0 || before < 0) {
          return null;
        }
        SignalBar base = bars.get(before);
        SignalBar r = bars.get(react);
        return new Sample(
            e.periodText(),
            e.pubDate(),
            e.timing(),
            r.date(),
            r.open() / base.close() - 1,
            r.close() / base.close() - 1);
      }
      case AFTER_MARKET -> {
        int baseIdx = on >= 0 ? on : before;
        if (after < 0 || baseIdx < 0) {
          return null;
        }
        SignalBar base = bars.get(baseIdx);
        SignalBar r = bars.get(after);
        return new Sample(
            e.periodText(),
            e.pubDate(),
            e.timing(),
            r.date(),
            r.open() / base.close() - 1,
            r.close() / base.close() - 1);
      }
      default -> {
        // Unknown timing: compute both, take the day with the larger move (the real reaction day is
        // usually the one that moved more)
        Sample pre =
            on >= 0 && before >= 0
                ? new Sample(
                    e.periodText(),
                    e.pubDate(),
                    e.timing(),
                    bars.get(on).date(),
                    bars.get(on).open() / bars.get(before).close() - 1,
                    bars.get(on).close() / bars.get(before).close() - 1)
                : null;
        int baseIdx = on >= 0 ? on : before;
        Sample post =
            after >= 0 && baseIdx >= 0
                ? new Sample(
                    e.periodText(),
                    e.pubDate(),
                    e.timing(),
                    bars.get(after).date(),
                    bars.get(after).open() / bars.get(baseIdx).close() - 1,
                    bars.get(after).close() / bars.get(baseIdx).close() - 1)
                : null;
        if (pre == null || post == null) {
          // No bar for the next day yet (or none on the announcement day itself): the reaction day
          // is not known yet, so the event is left out
          return null;
        }
        return Math.abs(post.move()) >= Math.abs(pre.move()) ? post : pre;
      }
    }
  }

  /** Returns null when there are no samples. */
  public static Stats stats(List<Sample> samples) {
    if (samples.isEmpty()) {
      return null;
    }
    double[] abs = samples.stream().mapToDouble(s -> Math.abs(s.move())).sorted().toArray();
    double mean = 0;
    for (double a : abs) {
      mean += a;
    }
    mean /= abs.length;
    double median =
        abs.length % 2 == 1
            ? abs[abs.length / 2]
            : (abs[abs.length / 2 - 1] + abs[abs.length / 2]) / 2;
    double min = samples.stream().mapToDouble(Sample::move).min().orElseThrow();
    double max = samples.stream().mapToDouble(Sample::move).max().orElseThrow();
    int big = (int) samples.stream().filter(s -> Math.abs(s.move()) >= BIG_MOVE).count();
    int ups = (int) samples.stream().filter(s -> s.move() > 0).count();
    int downs = (int) samples.stream().filter(s -> s.move() < 0).count();
    Double worstGap =
        samples.stream().map(Sample::gap).filter(g -> g != null).min(Double::compare).orElse(null);
    return new Stats(
        samples.size(),
        mean,
        median,
        Math.max(0, -min),
        Math.max(0, max),
        big,
        ups,
        downs,
        worstGap);
  }
}
