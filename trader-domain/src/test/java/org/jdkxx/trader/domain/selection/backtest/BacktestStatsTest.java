package org.jdkxx.trader.domain.selection.backtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.jdkxx.trader.domain.selection.Factor;
import org.jdkxx.trader.domain.selection.Rating;
import org.junit.jupiter.api.Test;

class BacktestStatsTest {

  private static BacktestStats.Obs obs(
      long id, String sector, Rating rating, double composite, double ret, double base) {
    Map<Factor, Double> f = new EnumMap<>(Factor.class);
    f.put(Factor.VALUE, composite);
    f.put(Factor.MOMENTUM, -composite);
    return new BacktestStats.Obs(id, sector, rating, composite, f, Map.of(), ret, base);
  }

  /** Hand calculation in the comment of the design: ranks (0.5, 0.5, 2..9) vs (0..9). */
  @Test
  void 秩相关并列取平均秩() {
    List<BacktestStats.Obs> o = new ArrayList<>();
    double[] x = {1, 1, 2, 3, 4, 5, 6, 7, 8, 9};
    for (int i = 0; i < 10; i++) {
      o.add(obs(i, "IT", Rating.HOLD, x[i], i + 1, Double.NaN));
    }
    assertThat(BacktestStats.spearman(o, BacktestStats.Obs::composite))
        .isCloseTo(Math.sqrt(82.0 / 82.5), within(1e-12));
    assertThat(BacktestStats.spearman(o.subList(0, 9), BacktestStats.Obs::composite)).isNaN();
  }

  @Test
  void 汇总的t值与命中率() {
    BacktestStats.Summary s = BacktestStats.summarize(List.of(0.01, 0.03, Double.NaN));
    assertThat(s.n()).isEqualTo(2);
    assertThat(s.mean()).isCloseTo(0.02, within(1e-12));
    assertThat(s.t()).isCloseTo(2.0, within(1e-9));
    assertThat(s.hit()).isEqualTo(1.0);
  }

  /**
   * Two sectors; SB in IT earns 0.10 against an IT mean of 0.04, SS in FIN earns -0.02 against a
   * FIN mean of 0.01.
   */
  @Test
  void 超额按同板块等权_分组与价差() {
    List<BacktestStats.Obs> d1 =
        List.of(
            obs(1, "IT", Rating.STRONG_BUY, 90, 0.10, 0.05),
            obs(2, "IT", Rating.HOLD, 50, 0.02, Double.NaN),
            obs(3, "IT", Rating.HOLD, 40, 0.00, Double.NaN),
            obs(4, "FIN", Rating.HOLD, 60, 0.04, Double.NaN),
            obs(5, "FIN", Rating.STRONG_SELL, 10, -0.02, Double.NaN));
    BacktestStats.Report r =
        BacktestStats.report(
            List.of(
                new BacktestStats.DateSample(
                    LocalDate.of(2020, 1, 2), d1, Map.of(Rating.STRONG_BUY, 1))));

    Map<Rating, BacktestStats.Summary> sector = r.ratingExcess().get(BacktestStats.Excess.SECTOR);
    assertThat(sector.get(Rating.STRONG_BUY).mean()).isCloseTo(0.06, within(1e-12));
    assertThat(sector.get(Rating.STRONG_SELL).mean()).isCloseTo(-0.03, within(1e-12));
    assertThat(r.sbMinusSs().get(BacktestStats.Excess.SECTOR).mean())
        .isCloseTo(0.09, within(1e-12));
    assertThat(r.ratingExcess().get(BacktestStats.Excess.NAME_YEAR).get(Rating.STRONG_BUY).mean())
        .isCloseTo(0.05, within(1e-12));
    assertThat(r.ratingExcess().get(BacktestStats.Excess.NAME_YEAR)).doesNotContainKey(Rating.HOLD);
    assertThat(r.attrition()).containsEntry(Rating.STRONG_BUY, 1);
    assertThat(r.bySector().get("IT").meanSbSectorExcess()).isCloseTo(0.06, within(1e-12));
    assertThat(r.factorCorrelation().get("VALUE~MOMENTUM")).isCloseTo(-1.0, within(1e-12));
  }

  @Test
  void SB名单换手() {
    LocalDate d = LocalDate.of(2020, 1, 2);
    BacktestStats.Report r =
        BacktestStats.report(
            List.of(
                new BacktestStats.DateSample(
                    d,
                    List.of(
                        obs(1, "IT", Rating.STRONG_BUY, 90, 0.1, 0),
                        obs(2, "IT", Rating.STRONG_BUY, 80, 0.1, 0)),
                    Map.of()),
                new BacktestStats.DateSample(
                    d.plusDays(63),
                    List.of(
                        obs(1, "IT", Rating.STRONG_BUY, 90, 0.1, 0),
                        obs(3, "IT", Rating.STRONG_BUY, 80, 0.1, 0)),
                    Map.of())));
    assertThat(r.sbTurnover().mean()).isCloseTo(0.5, within(1e-12));
  }

  @Test
  void 单调性_SB到SS严格递减为正一() {
    Map<Rating, BacktestStats.Summary> m = new EnumMap<>(Rating.class);
    double[] means = {0.05, 0.02, 0.0, -0.01, -0.04};
    for (Rating r : Rating.values()) {
      m.put(r, new BacktestStats.Summary(10, means[r.ordinal()], 0.01, 1, 0.5));
    }
    assertThat(BacktestStats.monotonicity(m)).isCloseTo(1.0, within(1e-12));
  }
}
