package org.jdkxx.trader.domain.signal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.jdkxx.trader.domain.signal.GateResult.Gate;
import org.jdkxx.trader.domain.signal.GateResult.Verdict;
import org.junit.jupiter.api.Test;

class SentinelEvaluatorTest {

  private static final SentinelThresholds TH = SentinelThresholds.V1;

  /**
   * Synthetic market: the first 200 bars rise linearly from 100 to about 150, then oscillate
   * sinusoidally around 155 with period 20, all troughs near 150, forming a support zone that is
   * touched repeatedly. The last bar is given by the parameters.
   */
  private static List<SignalBar> series(
      int size, double lastOpen, double lastLow, double lastClose, double lastVolume) {
    List<SignalBar> bars = new ArrayList<>();
    LocalDate d = LocalDate.of(2025, 1, 6);
    for (int i = 0; i < size - 1; i++) {
      double p = i < 200 ? 100 + 0.25 * i : 155 + 5 * Math.sin(2 * Math.PI * (i - 200) / 20.0);
      bars.add(new SignalBar(d, p, p + 1, p - 1, p, 1_000_000));
      d = nextWeekday(d);
    }
    double high = Math.max(lastOpen, lastClose) + 0.5;
    bars.add(new SignalBar(d, lastOpen, high, lastLow, lastClose, lastVolume));
    return bars;
  }

  private static LocalDate nextWeekday(LocalDate d) {
    LocalDate n = d.plusDays(1);
    while (n.getDayOfWeek() == DayOfWeek.SATURDAY || n.getDayOfWeek() == DayOfWeek.SUNDAY) {
      n = n.plusDays(1);
    }
    return n;
  }

  private static LocalDate last(List<SignalBar> bars) {
    return bars.get(bars.size() - 1).date();
  }

  private static GateResult gate(SentinelEvaluation e, Gate g) {
    return e.gates().stream().filter(x -> x.gate() == g).findFirst().orElseThrow();
  }

  @Test
  void 回踩支撑区放量收阳时四门全过() {
    List<SignalBar> bars = series(300, 149.5, 148.5, 152, 3_000_000);

    SentinelEvaluation e = SentinelEvaluator.evaluate(bars, last(bars), TH);

    assertThat(e.status()).isEqualTo(SentinelEvaluation.Status.EVALUATED);
    assertThat(e.gates()).extracting(GateResult::verdict).containsOnly(Verdict.PASS);
    assertThat(e.allPassed()).isTrue();
    assertThat(e.hitZone()).isNotNull();
    assertThat(e.hitZone().touches()).isGreaterThanOrEqualTo(2);
    assertThat(e.exitPlan().initialStop()).isLessThan(152);
    assertThat(e.version()).isEqualTo("sentinel-v1");
    assertThat(gate(e, Gate.TREND).criteria()).startsWith("收盘 152 > SMA200");
  }

  @Test
  void 量比不足时只有触发门不过且其余门照常计算() {
    List<SignalBar> bars = series(300, 149.5, 148.5, 152, 1_200_000);

    SentinelEvaluation e = SentinelEvaluator.evaluate(bars, last(bars), TH);

    assertThat(e.firstBlockingGate()).contains(Gate.TRIGGER);
    assertThat(e.gatesPassed()).isEqualTo(3);
    assertThat(gate(e, Gate.TRIGGER).criteria()).contains("= 1.20 < 1.5");
  }

  @Test
  void 收阴不触发() {
    List<SignalBar> bars = series(300, 153, 148.5, 152, 3_000_000);

    assertThat(SentinelEvaluator.evaluate(bars, last(bars), TH).firstBlockingGate())
        .contains(Gate.TRIGGER);
  }

  @Test
  void 当日最低没进入任何区时定位不过并报出下方最近的区() {
    List<SignalBar> bars = series(300, 151.5, 151.2, 153, 3_000_000);

    SentinelEvaluation e = SentinelEvaluator.evaluate(bars, last(bars), TH);

    assertThat(e.firstBlockingGate()).contains(Gate.LOCATION);
    assertThat(gate(e, Gate.LOCATION).criteria()).contains("未进入任何区").contains("最近支撑区");
    assertThat(e.hitZone()).isNull();
    // without a hit zone the risk gate uses the ATR leg only
    assertThat(gate(e, Gate.RISK).values().get("stopLeg")).isEqualTo("ATR");
  }

  @Test
  void K线不足不予判定() {
    List<SignalBar> bars = series(250, 149.5, 148.5, 152, 3_000_000);

    SentinelEvaluation e = SentinelEvaluator.evaluate(bars, last(bars), TH);

    assertThat(e.status()).isEqualTo(SentinelEvaluation.Status.SKIPPED_INSUFFICIENT_BARS);
    assertThat(e.gates()).isEmpty();
    assertThat(e.allPassed()).isFalse();
  }

  @Test
  void 判定日没有K线时判为陈旧() {
    List<SignalBar> bars = series(300, 149.5, 148.5, 152, 3_000_000);

    SentinelEvaluation e = SentinelEvaluator.evaluate(bars, last(bars).plusDays(1), TH);

    assertThat(e.status()).isEqualTo(SentinelEvaluation.Status.SKIPPED_STALE_DATA);
  }

  /**
   * Guard: the window is fixed to 600 calendar days before the evaluation day; earlier history must
   * not affect the conclusion (Wilder ATR and EMA are path-dependent).
   */
  @Test
  void 更长的历史不改变判定() {
    List<SignalBar> bars = series(700, 149.5, 148.5, 152, 3_000_000);
    List<SignalBar> tail = bars.subList(bars.size() - 460, bars.size());

    SentinelEvaluation full = SentinelEvaluator.evaluate(bars, last(bars), TH);
    SentinelEvaluation trimmed = SentinelEvaluator.evaluate(tail, last(bars), TH);

    assertThat(trimmed.indicators().get("windowStart"))
        .isEqualTo(full.indicators().get("windowStart"));
    assertThat(trimmed).isEqualTo(full);
  }

  /**
   * Guard: no look-ahead; appending any bars after the evaluation day never changes that day's
   * conclusion.
   */
  @Test
  void 判定日之后的K线不影响结论() {
    List<SignalBar> bars = series(300, 149.5, 148.5, 152, 3_000_000);
    LocalDate asOf = last(bars);
    List<SignalBar> extended = new ArrayList<>(bars);
    LocalDate d = asOf;
    for (int i = 0; i < 5; i++) {
      d = nextWeekday(d);
      extended.add(new SignalBar(d, 150, 170, 100, 160, 9_000_000));
    }

    assertThat(SentinelEvaluator.evaluate(extended, asOf, TH))
        .isEqualTo(SentinelEvaluator.evaluate(bars, asOf, TH));
  }

  /**
   * Guard: the lows of the evaluation day and the side bars before it must not enter a support zone
   * (a fractal needs two confirming bars on its right). Verified repeatedly on random markets.
   */
  @Test
  void 支撑区成员不含最后两根() {
    Random random = new Random(20260917);
    for (int round = 0; round < 200; round++) {
      List<SignalBar> bars = new ArrayList<>();
      LocalDate d = LocalDate.of(2025, 1, 6);
      double p = 100;
      for (int i = 0; i < 300; i++) {
        double next = Math.max(5, p + random.nextGaussian() * 2);
        bars.add(
            new SignalBar(
                d,
                p,
                Math.max(p, next) + random.nextDouble(),
                Math.min(p, next) - random.nextDouble(),
                next,
                1_000_000 + random.nextInt(500_000)));
        p = next;
        d = nextWeekday(d);
      }
      // push the last two bars to the window's lowest; with a wrong scan bound they would certainly
      // become fractal lows
      SignalBar b1 = bars.get(298);
      SignalBar b2 = bars.get(299);
      bars.set(298, new SignalBar(b1.date(), 2, 3, 1.0, 2, 1_000_000));
      bars.set(299, new SignalBar(b2.date(), 2, 3, 1.5, 2, 1_000_000));
      bars.set(297, new SignalBar(bars.get(297).date(), 4, 5, 1.2, 4, 1_000_000));

      SentinelEvaluation e = SentinelEvaluator.evaluate(bars, b2.date(), TH);

      assertThat(e.zones())
          .allSatisfy(z -> assertThat(z.members()).doesNotContain(b1.date(), b2.date()));
    }
  }

  /**
   * Guard: the support-zone lookback boundary matches entry-v3 (t − j &lt;= 252). Reconciling with
   * futu-trader on 2026-09-17 found a lookback one bar short, which changed the location or risk
   * conclusion on 7 trading days across MSFT/ISRG/WMT.
   */
  @Test
  void 支撑区回看含判定日往前第252根() {
    for (int age : new int[] {252, 253}) {
      List<SignalBar> bars = new ArrayList<>();
      LocalDate d = LocalDate.of(2025, 1, 6);
      int n = 300;
      for (int i = 0; i < n; i++) {
        double low = 100;
        int t = n - 1;
        if (i == t - age || i == t - 5) {
          low = 50; // two fractal lows at the same price: one on the lookback boundary, one nearby
        }
        bars.add(new SignalBar(d, 101, 102, low, 101, 1_000_000));
        d = nextWeekday(d);
      }

      SentinelEvaluation e = SentinelEvaluator.evaluate(bars, last(bars), TH);

      assertThat(e.zones()).as("age=" + age).hasSize(age == 252 ? 1 : 0);
    }
  }

  /**
   * Guard: a true tie between moving averages is not judged "greater" because of floating-point
   * noise (a measured tie for WMT on 2021-11-04).
   */
  @Test
  void 均线平局按不大于处理() {
    assertThat(SentinelEvaluator.greater(141.12535000000001, 141.12535)).isFalse();
    assertThat(SentinelEvaluator.greater(0.1 + 0.2, 0.3)).isFalse();
    assertThat(SentinelEvaluator.greater(141.1254, 141.1253)).isTrue();
  }

  @Test
  void 乱序输入直接报错() {
    List<SignalBar> bars = new ArrayList<>(series(300, 149.5, 148.5, 152, 3_000_000));
    java.util.Collections.swap(bars, 10, 11);

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> SentinelEvaluator.evaluate(bars, last(bars), TH))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
