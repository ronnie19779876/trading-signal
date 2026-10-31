package org.jdkxx.trader.domain.selection.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.jdkxx.trader.domain.signal.SignalBar;
import org.junit.jupiter.api.Test;

class SimPathTest {

  private static final SimRules RULES = SimRules.V1;

  /** Scoring day */
  private static final LocalDate D = LocalDate.of(2026, 9, 1);

  /**
   * 30 flat bars before the scoring day (high-low range 2 each, ATR14 = 2), scoring-day close 100;
   * later bars come from each test.
   */
  private static List<SignalBar> history() {
    List<SignalBar> out = new ArrayList<>();
    for (int i = 30; i >= 1; i--) {
      out.add(new SignalBar(D.minusDays(i), 100, 101, 99, 100, 1000));
    }
    out.add(new SignalBar(D, 100, 101, 99, 100, 1000));
    return out;
  }

  private static List<SignalBar> with(SignalBar... after) {
    List<SignalBar> out = history();
    out.addAll(List.of(after));
    return out;
  }

  private static SignalBar bar(int day, double o, double h, double l, double c) {
    return new SignalBar(D.plusDays(day), o, h, l, c, 1000);
  }

  @Test
  void 入场日的K线还没有_待成交() {
    SimPath.Path p = SimPath.walk(history(), D, D.plusDays(1), 96, null, null, RULES);
    assertThat(p.state()).isEqualTo(SimPath.State.PENDING);
    assertThat(p.entry()).isNull();
  }

  @Test
  void 开盘不高于止损_取消不入场() {
    SimPath.Path p =
        SimPath.walk(with(bar(1, 95.5, 97, 94, 96)), D, D.plusDays(1), 96, null, null, RULES);
    assertThat(p.state()).isEqualTo(SimPath.State.CANCELLED);
    assertThat(p.cancelReason()).contains("95.5").contains("96");
  }

  @Test
  void 入场取日期不早于计划日的第一根开盘() {
    // planned day D+3 has no bar (a weekend or similar): rolls forward to the D+5 bar
    SimPath.Path p =
        SimPath.walk(
            with(bar(1, 101, 102, 100, 101), bar(5, 102, 103, 101, 102)),
            D,
            D.plusDays(3),
            96,
            null,
            null,
            RULES);
    assertThat(p.state()).isEqualTo(SimPath.State.OPEN);
    assertThat(p.entryDate()).isEqualTo(D.plusDays(5));
    assertThat(p.entry()).isEqualTo(102.0);
  }

  /** Gap through the stop: booked at that day's close, not pretending to fill at the stop. */
  @Test
  void 收盘跌破止损按收盘平仓_跳空照实记() {
    SimPath.Path p =
        SimPath.walk(
            with(bar(1, 100, 101, 99, 100), bar(2, 92, 93, 90, 91)),
            D,
            D.plusDays(1),
            96,
            null,
            null,
            RULES);
    assertThat(p.state()).isEqualTo(SimPath.State.CLOSED);
    assertThat(p.reason()).isEqualTo(SimPath.ExitReason.STOP);
    assertThat(p.exit()).isEqualTo(91.0);
    assertThat(p.exitDate()).isEqualTo(D.plusDays(2));
    assertThat(p.barsHeld()).isEqualTo(2);
    assertThat(p.closes()).containsKeys(D.plusDays(1), D.plusDays(2));
  }

  @Test
  void 盘中跌破止损但收盘收回_不平仓() {
    SimPath.Path p =
        SimPath.walk(
            with(bar(1, 100, 101, 99, 100), bar(2, 99, 100, 94, 97)),
            D,
            D.plusDays(1),
            96,
            null,
            null,
            RULES);
    assertThat(p.state()).isEqualTo(SimPath.State.OPEN);
    assertThat(p.lowest()).isEqualTo(94.0);
  }

  /**
   * Entry 100, stop 96, so R = 4 and +1R = 104. Day 2's high of 105 touches it, the chandelier =
   * 105 − 3×ATR rises above the initial stop; day 3 closes below it.
   */
  @Test
  void 触及加1R后吊灯止损上移_跌破按吊灯平仓() {
    SimPath.Path p =
        SimPath.walk(
            with(bar(1, 100, 101, 99, 100), bar(2, 101, 105, 99, 104), bar(3, 103, 103, 97, 98)),
            D,
            D.plusDays(1),
            96,
            null,
            null,
            RULES);
    assertThat(p.plusOneR()).isEqualTo(104.0);
    assertThat(p.touchedPlusOneR()).isTrue();
    assertThat(p.chandelierActive()).isTrue();
    assertThat(p.activeStop()).isGreaterThan(96.0).isLessThan(98.5);
    assertThat(p.reason()).isEqualTo(SimPath.ExitReason.CHANDELIER);
    assertThat(p.exit()).isEqualTo(98.0);
  }

  @Test
  void 没摸到加1R时吊灯不生效() {
    SimPath.Path p =
        SimPath.walk(
            with(
                bar(1, 100, 101, 99, 100), bar(2, 101, 103.9, 99, 103), bar(3, 102, 102, 96.5, 97)),
            D,
            D.plusDays(1),
            96,
            null,
            null,
            RULES);
    assertThat(p.touchedPlusOneR()).isFalse();
    assertThat(p.state()).isEqualTo(SimPath.State.OPEN);
    assertThat(p.activeStop()).isEqualTo(96.0);
  }

  @Test
  void 有压力区且在入场之上_目标取区底() {
    SimPath.Path p =
        SimPath.walk(with(bar(1, 100, 101, 99, 100)), D, D.plusDays(1), 96, 106.0, null, RULES);
    assertThat(p.target()).isEqualTo(106.0);
    assertThat(p.targetSource()).isEqualTo(SimPath.TargetSource.RESISTANCE);
  }

  @Test
  void 没有压力区_目标是入场加2R() {
    SimPath.Path p =
        SimPath.walk(with(bar(1, 100, 101, 99, 100)), D, D.plusDays(1), 96, null, null, RULES);
    assertThat(p.target()).isEqualTo(108.0);
    assertThat(p.targetSource()).isEqualTo(SimPath.TargetSource.FALLBACK_2R);
  }

  @Test
  void 开盘已越过压力区_目标改用加2R() {
    SimPath.Path p =
        SimPath.walk(with(bar(1, 102, 103, 101, 102)), D, D.plusDays(1), 96, 101.0, null, RULES);
    assertThat(p.targetSource()).isEqualTo(SimPath.TargetSource.FALLBACK_2R);
    assertThat(p.target()).isCloseTo(102 + 2 * 6.0, within(1e-12));
  }

  @Test
  void 止盈只记触及日不自动平仓() {
    SimPath.Path p =
        SimPath.walk(
            with(bar(1, 100, 101, 99, 100), bar(2, 101, 109, 100, 108), bar(3, 108, 110, 107, 109)),
            D,
            D.plusDays(1),
            96,
            null,
            null,
            RULES);
    assertThat(p.targetHitDate()).isEqualTo(D.plusDays(2));
    assertThat(p.state()).isEqualTo(SimPath.State.OPEN);
    assertThat(p.lastClose()).isEqualTo(109.0);
  }

  @Test
  void 手动平仓按请求之后的第一个开盘成交() {
    SimPath.Path p =
        SimPath.walk(
            with(bar(1, 100, 101, 99, 100), bar(2, 101, 109, 100, 108), bar(3, 107, 110, 106, 109)),
            D,
            D.plusDays(1),
            96,
            null,
            D.plusDays(3),
            RULES);
    assertThat(p.state()).isEqualTo(SimPath.State.CLOSED);
    assertThat(p.reason()).isEqualTo(SimPath.ExitReason.REQUESTED);
    assertThat(p.exit()).isEqualTo(107.0);
    assertThat(p.exitDate()).isEqualTo(D.plusDays(3));
    // the day closed at the open does not count as held to the close
    assertThat(p.closes()).containsOnlyKeys(D.plusDays(1), D.plusDays(2));
  }

  /**
   * Within one bar the open comes before the close: the request fills at that day's open, and a
   * close below the stop that day no longer counts.
   */
  @Test
  void 平仓日开盘先成交_收盘止损不再算() {
    SimPath.Path p =
        SimPath.walk(
            with(bar(1, 100, 101, 99, 100), bar(2, 99, 99, 90, 91)),
            D,
            D.plusDays(1),
            96,
            null,
            D.plusDays(2),
            RULES);
    assertThat(p.reason()).isEqualTo(SimPath.ExitReason.REQUESTED);
    assertThat(p.exit()).isEqualTo(99.0);
  }

  /**
   * The stop happens before the requested exit day: closed by the stop, the request comes to
   * nothing.
   */
  @Test
  void 平仓日之前已止损_按止损() {
    SimPath.Path p =
        SimPath.walk(
            with(bar(1, 100, 101, 99, 100), bar(2, 97, 97, 94, 95), bar(3, 96, 97, 95, 96)),
            D,
            D.plusDays(1),
            96,
            null,
            D.plusDays(3),
            RULES);
    assertThat(p.reason()).isEqualTo(SimPath.ExitReason.STOP);
    assertThat(p.exitDate()).isEqualTo(D.plusDays(2));
  }

  /**
   * The request date is not after the entry date (exit clicked on the entry day): not closed on the
   * entry bar, closed at the next bar's open.
   */
  @Test
  void 请求不晚于入场日_入场后下一根开盘平() {
    SimPath.Path p =
        SimPath.walk(
            with(bar(1, 100, 101, 99, 100), bar(2, 102, 103, 101, 102)),
            D,
            D.plusDays(1),
            96,
            null,
            D.plusDays(1),
            RULES);
    assertThat(p.entryDate()).isEqualTo(D.plusDays(1));
    assertThat(p.exitDate()).isEqualTo(D.plusDays(2));
    assertThat(p.exit()).isEqualTo(102.0);
  }

  @Test
  void 乱序K线直接报错() {
    List<SignalBar> bars = with(bar(2, 100, 101, 99, 100), bar(1, 100, 101, 99, 100));
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> SimPath.walk(bars, D, D.plusDays(1), 96, null, null, RULES))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
