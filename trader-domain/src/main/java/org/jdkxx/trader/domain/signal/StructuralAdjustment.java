package org.jdkxx.trader.domain.signal;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.RehabFactor;

/**
 * Price/volume basis for signal evaluation (pure function): the <b>structural basis</b>, a forward
 * adjustment anchored on the evaluation day that adjusts only structural events and never ordinary
 * dividends.
 *
 * <ul>
 *   <li><b>Price</b>: events carrying any of the split, consolidation, bonus share, capitalization
 *       issue, spin-off or special dividend bits are adjusted. Pure share-count events use the
 *       exact ratio (Futu's fwdA keeps only 5 decimal places); events mixed with a spin-off or a
 *       dividend use Futu's factor p' = p × fwdA + fwdB; events with only an ordinary dividend are
 *       not adjusted. Spin-offs and special dividends make the raw price gap (measured: DD spin-off
 *       on 2025-11-03, close 81.65 → 34.69); adjusting for splits only would distort SMA200, ATR
 *       and support zones for roughly 200 trading days after the event (user decision of
 *       2026-09-17);
 *   <li><b>Volume</b>: adjusted only by the share-count ratio (split and consolidation ert/base,
 *       bonus shares (base+ert)/base); spin-offs and dividends do not change the share count. fwdA
 *       cannot be used for volume: a consolidation and a spin-off can be in the same event
 *       (measured: HON 2026-06-29 flag=258, consolidation 2:1, fwdA=1.09203);
 *   <li><b>Only events going ex on or before the evaluation day are used</b>: later events are
 *       future information, so the evaluation-day bar is always the raw price and the stop is a
 *       price that was actually tradable then.
 * </ul>
 *
 * An event with a share-count bit but no ratio (factors stored before V10 and not yet re-fetched),
 * or a capitalization issue event (never verified), makes the whole conversion return a problem
 * instead of guessing. Blank bars are dropped.
 */
public final class StructuralAdjustment {

  private static final long STRUCTURAL =
      RehabFactor.ACT_SPLIT
          | RehabFactor.ACT_JOIN
          | RehabFactor.ACT_BONUS
          | RehabFactor.ACT_TRANSFER
          | RehabFactor.ACT_SPIN_OFF
          | RehabFactor.ACT_SP_DIVIDEND;

  private StructuralAdjustment() {}

  /**
   * Either {@code bars} or {@code problem} is meaningful, never both; {@link #ok()} tells which.
   */
  public record Result(List<SignalBar> bars, String problem) {
    public boolean ok() {
      return problem == null;
    }
  }

  public static Result apply(List<DailyBar> raw, List<RehabFactor> factors, LocalDate asOf) {
    LocalDate earliest =
        raw.stream()
            .filter(b -> !b.blank())
            .map(DailyBar::tradeDate)
            .min(LocalDate::compareTo)
            .orElse(asOf);
    List<Event> events = new ArrayList<>();
    for (RehabFactor f : factors) {
      // Only events that act on these bars matter: ex-date after the earliest bar and not after the
      // evaluation day. Earlier events do not change the result, so a missing ratio there must not
      // block evaluation
      if (!f.exDate().isAfter(earliest)
          || f.exDate().isAfter(asOf)
          || (f.companyActFlag() & STRUCTURAL) == 0) {
        continue;
      }
      if (f.has(RehabFactor.ACT_TRANSFER)) {
        return new Result(List.of(), f.exDate() + " 转增事件的口径未实测（flag=" + f.companyActFlag() + "）");
      }
      double shares = 1;
      if (f.has(RehabFactor.ACT_SPLIT)) {
        if (f.splitBase() <= 0 || f.splitErt() <= 0) {
          return missing(f);
        }
        shares *= (double) f.splitErt() / f.splitBase();
      }
      if (f.has(RehabFactor.ACT_JOIN)) {
        if (f.joinBase() <= 0 || f.joinErt() <= 0) {
          return missing(f);
        }
        shares *= (double) f.joinErt() / f.joinBase();
      }
      if (f.has(RehabFactor.ACT_BONUS)) {
        if (f.bonusBase() <= 0 || f.bonusErt() <= 0) {
          return missing(f);
        }
        shares *= (double) (f.bonusBase() + f.bonusErt()) / f.bonusBase();
      }
      long cashLike =
          RehabFactor.ACT_SPIN_OFF | RehabFactor.ACT_SP_DIVIDEND | RehabFactor.ACT_DIVIDEND;
      boolean pureShareChange = (f.companyActFlag() & cashLike) == 0 && f.fwdB().signum() == 0;
      // Pure share-count changes use the exact ratio: Futu's fwdA keeps only 5 decimal places
      // (WMT's 1:3 split gives 0.33333), which shifts prices across the event by 1e-5, enough to
      // flip strict comparisons at a boundary
      double a = pureShareChange ? 1 / shares : f.fwdA().doubleValue();
      events.add(new Event(f.exDate(), a, pureShareChange ? 0 : f.fwdB().doubleValue(), shares));
    }
    events.sort(Comparator.comparing(Event::exDate));

    List<SignalBar> out = new ArrayList<>(raw.size());
    for (DailyBar b : raw) {
      if (b.blank() || b.tradeDate().isAfter(asOf)) {
        continue;
      }
      double a = 1;
      double bb = 0;
      double shares = 1;
      // Apply earlier events first, then later ones: p' = A2 (A1 p + B1) + B2 (Futu factors are per
      // event and compose event by event, see FactorMode). Only events with ex-date after this
      // bar's date apply
      for (Event e : events) {
        if (e.exDate().isAfter(b.tradeDate())) {
          bb = e.a() * bb + e.b();
          a = e.a() * a;
          shares *= e.shares();
        }
      }
      out.add(
          new SignalBar(
              b.tradeDate(),
              b.open().doubleValue() * a + bb,
              b.high().doubleValue() * a + bb,
              b.low().doubleValue() * a + bb,
              b.close().doubleValue() * a + bb,
              b.volume() * shares));
    }
    return new Result(out, null);
  }

  private static Result missing(RehabFactor f) {
    return new Result(
        List.of(), f.exDate() + " 的股数变动事件缺比例（flag=" + f.companyActFlag() + "），等复权因子重拉");
  }

  private record Event(LocalDate exDate, double a, double b, double shares) {}
}
