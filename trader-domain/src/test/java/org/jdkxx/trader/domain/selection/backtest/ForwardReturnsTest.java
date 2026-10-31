package org.jdkxx.trader.domain.selection.backtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.selection.AdjustedPrices;
import org.junit.jupiter.api.Test;

class ForwardReturnsTest {

  private static final Instrument X = Instrument.us("X");
  private static final LocalDate D0 = LocalDate.of(2022, 12, 26);

  /** Ten consecutive calendar days used as trading days (D0 .. D0+9). */
  private static List<LocalDate> calendar() {
    List<LocalDate> c = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      c.add(D0.plusDays(i));
    }
    return c;
  }

  private static AdjustedPrices prices(double... closes) {
    List<DailyBar> bars = new ArrayList<>();
    for (int i = 0; i < closes.length; i++) {
      BigDecimal c = BigDecimal.valueOf(closes[i]);
      bars.add(new DailyBar(X, D0.plusDays(i), c, c, c, c, null, 1, null, null, null, null, false));
    }
    return AdjustedPrices.build(bars, List.of(), List.of());
  }

  @Test
  void 入场是评分日后第一个交易日_出场再过h个交易日() {
    ForwardReturns.Window w = ForwardReturns.window(calendar(), D0, 3, null).orElseThrow();
    assertThat(w.entry()).isEqualTo(D0.plusDays(1));
    assertThat(w.exit()).isEqualTo(D0.plusDays(4));
    assertThat(ForwardReturns.window(calendar(), D0.plusDays(7), 3, null)).isEmpty();
  }

  /** Training samples must exit before the holdout starts (2023-01-01 = D0 + 6). */
  @Test
  void 出场日跨进留出段的样本丢掉() {
    LocalDate holdout = LocalDate.of(2023, 1, 1);
    assertThat(ForwardReturns.window(calendar(), D0, 4, holdout)).isPresent(); // exit D0+5
    assertThat(ForwardReturns.window(calendar(), D0, 5, holdout)).isEmpty(); // exit D0+6
  }

  @Test
  void 收益按入场与出场收盘_缺价为NaN() {
    AdjustedPrices p = prices(100, 110, 120, 130, 140);
    ForwardReturns.Window w = new ForwardReturns.Window(D0.plusDays(1), D0.plusDays(3));
    assertThat(ForwardReturns.of(p, w, true, 7)).isCloseTo(130.0 / 110 - 1, within(1e-12));

    // Exit beyond the last bar by more than staleDays: missing
    ForwardReturns.Window late = new ForwardReturns.Window(D0.plusDays(1), D0.plusDays(20));
    assertThat(ForwardReturns.of(p, late, true, 7)).isNaN();
    // Both ends on the same bar (suspended): missing
    ForwardReturns.Window flat = new ForwardReturns.Window(D0.plusDays(5), D0.plusDays(6));
    assertThat(ForwardReturns.of(p, flat, true, 7)).isNaN();
  }

  /**
   * 2022 entries D0..D0+5 with h = 1 inside the calendar: returns 1/10, 1/11, 1/12, 1/13 average.
   */
  @Test
  void 同标的同年基准是当年任意一天入场的平均() {
    AdjustedPrices p = prices(100, 110, 120, 130, 140);
    double base = ForwardReturns.nameYearBaseline(p, calendar(), 2022, 1, true, 0, null);
    assertThat(base).isCloseTo((0.1 + 1.0 / 11 + 1.0 / 12 + 1.0 / 13) / 4, within(1e-12));
  }
}
