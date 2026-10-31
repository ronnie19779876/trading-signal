package org.jdkxx.trader.domain.selection.sim;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

/**
 * Cash and equity of the simulated account (pure functions). Everything is derived from the
 * individual fills and no balance is stored: when any fill is recomputed, the balances follow
 * automatically.
 *
 * <p>Order within one day: exits at the open first (manual / take-profit requests), then entries in
 * order of request time, then stop exits at the close. Cash available to an entry at the open
 * therefore includes the trades closed at that day's open but not those stopped out at that day's
 * close.
 */
public final class SimLedger {

  private SimLedger() {}

  /**
   * One filled entry (price and shares on the scoring-day basis: both are scaled together in
   * opposite directions, so amounts are unaffected by splits during the holding period).
   *
   * @param exitAtOpen the exit happened at the open (manual / take-profit request); false means at
   *     the close (stop)
   */
  public record Fill(
      long id,
      Instant requestedAt,
      LocalDate entryDate,
      double entry,
      int shares,
      LocalDate exitDate,
      Double exit,
      boolean exitAtOpen) {

    double cost() {
      return shares * entry;
    }

    double proceeds() {
      return exit == null ? 0 : shares * exit;
    }

    double pnl() {
      return exit == null ? 0 : shares * (exit - entry);
    }

    /**
     * Already closed before the entries at {@code day}'s open (any earlier day, or at that day's
     * open)
     */
    boolean exitedBeforeOpen(LocalDate day) {
      return exitDate != null && (exitDate.isBefore(day) || (exitDate.equals(day) && exitAtOpen));
    }

    boolean exitedByClose(LocalDate day) {
      return exitDate != null && !exitDate.isAfter(day);
    }

    boolean heldAtClose(LocalDate day) {
      return !entryDate.isAfter(day) && !exitedByClose(day);
    }
  }

  /** Realized P&amp;L before the entries at {@code day}'s open. */
  public static double realizedBeforeOpen(Collection<Fill> fills, LocalDate day) {
    return fills.stream().filter(f -> f.exitedBeforeOpen(day)).mapToDouble(Fill::pnl).sum();
  }

  /**
   * Cash available at {@code day}'s open when it is the turn of the entry requested at {@code
   * requestedAt}: minus the cost of earlier entries (earlier days, or the same day's open with an
   * earlier request time), plus the proceeds of everything closed before that open. Entries with an
   * identical request time do not see each other.
   */
  public static double cashAtOpen(
      Collection<Fill> fills, LocalDate day, Instant requestedAt, double initial) {
    double cash = initial;
    for (Fill f : fills) {
      if (f.entryDate().isBefore(day)
          || (f.entryDate().equals(day) && f.requestedAt().isBefore(requestedAt))) {
        cash -= f.cost();
      }
      if (f.exitedBeforeOpen(day)) {
        cash += f.proceeds();
      }
    }
    return cash;
  }

  /**
   * Equal-weight share count: notional = min((initial + realized) ÷ slots, available cash), rounded
   * down to whole shares.
   */
  public static int shares(double capital, int slots, double cash, double entry) {
    double notional = Math.min(capital / slots, cash);
    return notional <= 0 || entry <= 0 ? 0 : (int) Math.floor(notional / entry);
  }

  /** Cash, position market value and number of open positions after a day's close. */
  public record Equity(LocalDate date, double cash, double positions, int open) {
    public double total() {
      return cash + positions;
    }
  }

  /**
   * @param closes daily closes per fill (scoring-day basis); when a day has no bar (suspension) the
   *     nearest earlier one is used, and the entry price when there is none at all
   */
  public static Equity equityAtClose(
      Collection<Fill> fills,
      LocalDate day,
      double initial,
      Map<Long, NavigableMap<LocalDate, Double>> closes) {
    double cash = initial;
    double positions = 0;
    int open = 0;
    for (Fill f : fills) {
      if (f.entryDate().isAfter(day)) {
        continue;
      }
      cash -= f.cost();
      if (f.exitedByClose(day)) {
        cash += f.proceeds();
      } else {
        NavigableMap<LocalDate, Double> c = closes.get(f.id());
        Map.Entry<LocalDate, Double> e = c == null ? null : c.floorEntry(day);
        positions += f.shares() * (e == null ? f.entry() : e.getValue());
        open++;
      }
    }
    return new Equity(day, cash, positions, open);
  }

  /** Maximum drawdown of an equity series (as a ratio, &lt;= 0); 0 with fewer than two points. */
  public static double maxDrawdown(List<Double> equity) {
    double peak = Double.NEGATIVE_INFINITY;
    double worst = 0;
    for (double v : equity) {
      peak = Math.max(peak, v);
      if (peak > 0) {
        worst = Math.min(worst, v / peak - 1);
      }
    }
    return worst;
  }
}
