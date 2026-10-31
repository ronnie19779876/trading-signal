package org.jdkxx.trader.domain.selection.backtest;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.jdkxx.trader.domain.selection.AdjustedPrices;

/**
 * Forward returns of the historical test (ARCHITECTURE §30.3). Scores come out at 18:30 on t, so
 * t's close cannot be traded: entry is the close of the first trading day after t, exit the close h
 * trading days after entry. A price on or within {@code staleDays} calendar days before entry /
 * exit counts; otherwise the return is missing (NaN) and the observation is counted as attrition.
 */
public final class ForwardReturns {

  private ForwardReturns() {}

  /** Entry and exit trading days of one sample. */
  public record Window(LocalDate entry, LocalDate exit) {}

  /**
   * The window for scoring day t over the trading calendar ({@code calendar} ascending), or empty
   * when the calendar does not reach h trading days past entry, or the exit would not be before
   * {@code exitBefore} (the training segment must not look into the holdout, §30.6; pass null for
   * no bound).
   */
  public static Optional<Window> window(
      List<LocalDate> calendar, LocalDate t, int h, LocalDate exitBefore) {
    int i = Collections.binarySearch(calendar, t);
    int entry = i >= 0 ? i + 1 : -i - 1;
    int exit = entry + h;
    if (exit >= calendar.size()) {
      return Optional.empty();
    }
    LocalDate x = calendar.get(exit);
    if (exitBefore != null && !x.isBefore(exitBefore)) {
      return Optional.empty();
    }
    return Optional.of(new Window(calendar.get(entry), x));
  }

  /**
   * Return of one instrument over the window; NaN when either end has no price within staleDays or
   * both ends resolve to the same bar (suspended through the whole window).
   *
   * @param total true for total return (cash dividends reinvested), false for split-adjusted price
   */
  public static double of(AdjustedPrices p, Window w, boolean total, int staleDays) {
    if (p == null || !p.valid() || p.size() == 0) {
      return Double.NaN;
    }
    int a = p.indexAt(w.entry(), staleDays);
    int b = p.indexAt(w.exit(), staleDays);
    if (a < 0 || b <= a) {
      return Double.NaN;
    }
    return total ? p.totalReturn(a, b) : p.priceReturn(a, b);
  }

  /**
   * Same-name same-year baseline (§30.5): the mean return of entering this instrument on any
   * trading day of {@code year} and holding h days, with the same exit bound as the samples. NaN
   * when no such entry has a return.
   */
  public static double nameYearBaseline(
      AdjustedPrices p,
      List<LocalDate> calendar,
      int year,
      int h,
      boolean total,
      int staleDays,
      LocalDate exitBefore) {
    double sum = 0;
    int n = 0;
    int from = Collections.binarySearch(calendar, LocalDate.of(year, 1, 1));
    from = from >= 0 ? from : -from - 1;
    for (int e = from; e + h < calendar.size() && calendar.get(e).getYear() == year; e++) {
      LocalDate x = calendar.get(e + h);
      if (exitBefore != null && !x.isBefore(exitBefore)) {
        break;
      }
      double r = of(p, new Window(calendar.get(e), x), total, staleDays);
      if (Double.isFinite(r)) {
        sum += r;
        n++;
      }
    }
    return n == 0 ? Double.NaN : sum / n;
  }
}
