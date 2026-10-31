package org.jdkxx.trader.domain.holding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDate;
import java.util.List;
import org.jdkxx.trader.domain.selection.EarningsEvent;
import org.jdkxx.trader.domain.selection.EarningsTiming;
import org.jdkxx.trader.domain.signal.SignalBar;
import org.junit.jupiter.api.Test;

class EarningsReactionTest {

  private static LocalDate d(String s) {
    return LocalDate.parse(s);
  }

  private static SignalBar bar(String date, double open, double close) {
    return new SignalBar(d(date), open, Math.max(open, close), Math.min(open, close), close, 1000);
  }

  private static EarningsEvent e(String period, String date, EarningsTiming timing) {
    return new EarningsEvent(period, 2026, d(date), null, timing, false);
  }

  /** 10-26 close 100; 10-27 open 98 close 95; 10-28 open 90 close 88; 10-29 open 89 close 90. */
  private static final List<SignalBar> BARS =
      List.of(
          bar("2026-10-26", 100, 100),
          bar("2026-10-27", 98, 95),
          bar("2026-10-28", 90, 88),
          bar("2026-10-29", 89, 90));

  @Test
  void 盘前公布看当天相对前收() {
    EarningsReaction.Sample s =
        EarningsReaction.sample(BARS, e("Q3", "2026-10-27", EarningsTiming.PRE_MARKET));

    assertThat(s.reactionDate()).isEqualTo(d("2026-10-27"));
    assertThat(s.gap()).isCloseTo(-0.02, within(1e-9));
    assertThat(s.move()).isCloseTo(-0.05, within(1e-9));
  }

  /**
   * The GOOG 2026/Q3 case: published after the close on 10-28, reaction on 10-29. Here published
   * after the close on 10-27, so the reaction day is 10-28, relative to the 10-27 close.
   */
  @Test
  void 盘后公布看下一个交易日相对公告日收盘() {
    EarningsReaction.Sample s =
        EarningsReaction.sample(BARS, e("Q3", "2026-10-27", EarningsTiming.AFTER_MARKET));

    assertThat(s.reactionDate()).isEqualTo(d("2026-10-28"));
    assertThat(s.gap()).isCloseTo(90.0 / 95 - 1, within(1e-9));
    assertThat(s.move()).isCloseTo(88.0 / 95 - 1, within(1e-9));
  }

  /**
   * Unknown timing: computed pre-market style (10-27 vs 10-26: −5%) and after-market style (10-28
   * vs 10-27: −7.37%), taking the larger move. A two-day window (10-28 vs 10-26: −12%) must not be
   * used: for GOOG 2022/Q3 the announcement day rose first, and combining the two days diluted
   * −9.63% to −7.91%.
   */
  @Test
  void 时段未知取两种口径里波动更大的一天() {
    EarningsReaction.Sample s =
        EarningsReaction.sample(BARS, e("Q3", "2026-10-27", EarningsTiming.UNKNOWN));

    assertThat(s.reactionDate()).isEqualTo(d("2026-10-28"));
    assertThat(s.move()).isCloseTo(88.0 / 95 - 1, within(1e-9));
    assertThat(s.gap()).isCloseTo(90.0 / 95 - 1, within(1e-9));
  }

  /** The big move is on the announcement day itself (next day calm): take that day. */
  @Test
  void 时段未知时当天波动更大就取当天() {
    List<SignalBar> bars =
        List.of(bar("2026-10-26", 100, 100), bar("2026-10-27", 99, 90), bar("2026-10-28", 90, 91));

    EarningsReaction.Sample s =
        EarningsReaction.sample(bars, e("Q3", "2026-10-27", EarningsTiming.UNKNOWN));

    assertThat(s.reactionDate()).isEqualTo(d("2026-10-27"));
    assertThat(s.move()).isCloseTo(-0.10, within(1e-9));
  }

  @Test
  void 反应日还没有K线的不计入() {
    assertThat(EarningsReaction.sample(BARS, e("Q4", "2026-10-29", EarningsTiming.AFTER_MARKET)))
        .isNull();
    assertThat(EarningsReaction.sample(BARS, e("Q4", "2027-01-28", EarningsTiming.PRE_MARKET)))
        .isNull();
    assertThat(EarningsReaction.sample(BARS, e("Q1", "2026-10-26", EarningsTiming.PRE_MARKET)))
        .isNull(); // no previous close
    assertThat(EarningsReaction.sample(BARS, e("Q4", "2026-10-29", EarningsTiming.UNKNOWN)))
        .isNull(); // no bar for the next day yet
  }

  @Test
  void 统计量() {
    List<EarningsReaction.Sample> samples =
        List.of(
            new EarningsReaction.Sample(
                "a", d("2026-01-01"), EarningsTiming.AFTER_MARKET, d("2026-01-02"), -0.07, -0.0963),
            new EarningsReaction.Sample(
                "b", d("2026-04-01"), EarningsTiming.AFTER_MARKET, d("2026-04-02"), 0.11, 0.0997),
            new EarningsReaction.Sample(
                "c", d("2026-07-01"), EarningsTiming.UNKNOWN, d("2026-07-02"), null, 0.0088),
            new EarningsReaction.Sample(
                "d",
                d("2026-10-01"),
                EarningsTiming.PRE_MARKET,
                d("2026-10-01"),
                -0.1182,
                -0.1229));

    EarningsReaction.Stats st = EarningsReaction.stats(samples);

    assertThat(st.samples()).isEqualTo(4);
    assertThat(st.meanAbsMove()).isCloseTo((0.0963 + 0.0997 + 0.0088 + 0.1229) / 4, within(1e-9));
    assertThat(st.medianAbsMove()).isCloseTo((0.0963 + 0.0997) / 2, within(1e-9));
    assertThat(st.maxDrop()).isCloseTo(0.1229, within(1e-9));
    assertThat(st.maxRise()).isCloseTo(0.0997, within(1e-9));
    assertThat(st.bigMoves()).isEqualTo(3);
    assertThat(st.ups()).isEqualTo(2);
    assertThat(st.downs()).isEqualTo(2);
    assertThat(st.worstGap()).isCloseTo(-0.1182, within(1e-9));
    assertThat(EarningsReaction.stats(List.of())).isNull();
  }

  @Test
  void 从来没跌过时最大跌幅为零() {
    EarningsReaction.Stats st =
        EarningsReaction.stats(
            List.of(
                new EarningsReaction.Sample(
                    "a", d("2026-01-01"), EarningsTiming.UNKNOWN, d("2026-01-02"), null, 0.03)));

    assertThat(st.maxDrop()).isZero();
    assertThat(st.worstGap()).isNull();
  }

  @Test
  void 判定日当天盘前盘中算已反映_盘后与时段未知不算() {
    LocalDate asOf = d("2026-10-28");

    assertThat(EarningsReaction.reflected(e("x", "2026-10-27", EarningsTiming.AFTER_MARKET), asOf))
        .isTrue();
    assertThat(EarningsReaction.reflected(e("x", "2026-10-28", EarningsTiming.PRE_MARKET), asOf))
        .isTrue();
    assertThat(EarningsReaction.reflected(e("x", "2026-10-28", EarningsTiming.DURING_MARKET), asOf))
        .isTrue();
    assertThat(EarningsReaction.reflected(e("x", "2026-10-28", EarningsTiming.AFTER_MARKET), asOf))
        .isFalse();
    assertThat(EarningsReaction.reflected(e("x", "2026-10-28", EarningsTiming.UNKNOWN), asOf))
        .isFalse();
  }
}
