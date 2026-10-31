package org.jdkxx.trader.domain.selection.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class SimLedgerTest {

  private static final LocalDate D = LocalDate.of(2026, 9, 1);
  private static final Instant T0 = Instant.parse("2026-09-01T23:00:00Z");

  private static SimLedger.Fill fill(
      long id,
      int requestMinute,
      int entryDay,
      double entry,
      int shares,
      Integer exitDay,
      Double exit,
      boolean atOpen) {
    return new SimLedger.Fill(
        id,
        T0.plusSeconds(60L * requestMinute),
        D.plusDays(entryDay),
        entry,
        shares,
        exitDay == null ? null : D.plusDays(exitDay),
        exit,
        atOpen);
  }

  @Test
  void 等权股数_取名义与现金的小者向下取整() {
    assertThat(SimLedger.shares(500_000, 10, 500_000, 123.0)).isEqualTo(406); // 50000 / 123 = 406.5
    assertThat(SimLedger.shares(500_000, 10, 20_000, 100.0)).isEqualTo(200); // only 20k cash left
    assertThat(SimLedger.shares(500_000, 10, 0, 100.0)).isZero();
  }

  /**
   * Same day's open: requested exits first, then entries in request-time order; money from that
   * day's closing stop is not available at its open.
   */
  @Test
  void 开盘可用现金_含当天开盘平掉的_不含当天收盘止损的() {
    List<SimLedger.Fill> fills =
        List.of(
            fill(1, 0, 1, 100, 100, 3, 110.0, true), // closed at day 3's open: +11000 available
            fill(2, 1, 1, 50, 200, 3, 45.0, false), // stopped at day 3's close: not available
            fill(
                3, 5, 3, 20, 100, null, null,
                false)); // enters at day 3's open, requested at minute 5
    double initial = 100_000;
    // turn of an entry requested at minute 3 for day 3: 1 and 2 are deducted, 3 was requested later
    // and is not; 1 closed at the open is added back
    double cash = SimLedger.cashAtOpen(fills, D.plusDays(3), T0.plusSeconds(180), initial);
    assertThat(cash).isEqualTo(initial - 10_000 - 10_000 + 11_000);
    // turn of the one requested at minute 9: 3 is deducted too
    assertThat(SimLedger.cashAtOpen(fills, D.plusDays(3), T0.plusSeconds(540), initial))
        .isEqualTo(initial - 10_000 - 10_000 + 11_000 - 2_000);
    assertThat(SimLedger.realizedBeforeOpen(fills, D.plusDays(3))).isEqualTo(1_000.0);
    assertThat(SimLedger.realizedBeforeOpen(fills, D.plusDays(4))).isEqualTo(1_000.0 - 1_000.0);
  }

  @Test
  void 收盘权益_持仓按当天收盘_停牌用之前最近一根() {
    List<SimLedger.Fill> fills =
        List.of(fill(1, 0, 1, 100, 100, null, null, false), fill(2, 1, 1, 50, 200, 2, 45.0, false));
    NavigableMap<LocalDate, Double> c1 =
        new TreeMap<>(Map.of(D.plusDays(1), 101.0, D.plusDays(2), 103.0));
    Map<Long, NavigableMap<LocalDate, Double>> closes = Map.of(1L, c1);

    SimLedger.Equity e1 = SimLedger.equityAtClose(fills, D.plusDays(1), 100_000, closes);
    assertThat(e1.cash()).isEqualTo(80_000);
    assertThat(e1.open())
        .isEqualTo(2); // fill 2 has no close recorded for that day, so its entry price is used
    assertThat(e1.total()).isEqualTo(80_000 + 10_100 + 10_000);

    SimLedger.Equity e3 =
        SimLedger.equityAtClose(fills, D.plusDays(3), 100_000, closes); // suspended on day 3
    assertThat(e3.cash()).isEqualTo(80_000 + 9_000);
    assertThat(e3.positions()).isEqualTo(10_300);
    assertThat(e3.open()).isEqualTo(1);
  }

  @Test
  void 最大回撤() {
    assertThat(SimLedger.maxDrawdown(List.of(100.0, 110.0, 99.0, 120.0, 108.0)))
        .isCloseTo(99.0 / 110 - 1, within(1e-12));
    assertThat(SimLedger.maxDrawdown(List.of(100.0))).isZero();
  }
}
