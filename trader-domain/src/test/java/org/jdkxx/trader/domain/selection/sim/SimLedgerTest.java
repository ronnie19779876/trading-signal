package org.jdkxx.trader.domain.selection.sim;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SimLedgerTest {

    private static final LocalDate D = LocalDate.of(2026, 9, 1);
    private static final Instant T0 = Instant.parse("2026-09-01T23:00:00Z");

    private static SimLedger.Fill fill(long id, int requestMinute, int entryDay, double entry, int shares,
                                       Integer exitDay, Double exit, boolean atOpen) {
        return new SimLedger.Fill(id, T0.plusSeconds(60L * requestMinute), D.plusDays(entryDay), entry, shares,
                exitDay == null ? null : D.plusDays(exitDay), exit, atOpen);
    }

    @Test
    void 等权股数_取名义与现金的小者向下取整() {
        assertThat(SimLedger.shares(500_000, 10, 500_000, 123.0)).isEqualTo(406);      // 50000 / 123 = 406.5
        assertThat(SimLedger.shares(500_000, 10, 20_000, 100.0)).isEqualTo(200);       // 现金只剩 2 万
        assertThat(SimLedger.shares(500_000, 10, 0, 100.0)).isZero();
    }

    /** 同一天开盘：先平（请求平仓的）再入场，按请求时间先后；收盘止损的钱当天开盘用不上。 */
    @Test
    void 开盘可用现金_含当天开盘平掉的_不含当天收盘止损的() {
        List<SimLedger.Fill> fills = List.of(
                fill(1, 0, 1, 100, 100, 3, 110.0, true),     // 第 3 天开盘平：+11000 可用
                fill(2, 1, 1, 50, 200, 3, 45.0, false),      // 第 3 天收盘止损：不可用
                fill(3, 5, 3, 20, 100, null, null, false));  // 第 3 天开盘入场，请求在第 5 分钟
        double initial = 100_000;
        // 轮到一笔第 3 分钟请求、第 3 天入场的：1、2 已扣，3 请求更晚不扣；1 开盘平的加回
        double cash = SimLedger.cashAtOpen(fills, D.plusDays(3), T0.plusSeconds(180), initial);
        assertThat(cash).isEqualTo(initial - 10_000 - 10_000 + 11_000);
        // 轮到第 9 分钟请求的：3 也扣
        assertThat(SimLedger.cashAtOpen(fills, D.plusDays(3), T0.plusSeconds(540), initial)).isEqualTo(initial - 10_000 - 10_000 + 11_000 - 2_000);
        assertThat(SimLedger.realizedBeforeOpen(fills, D.plusDays(3))).isEqualTo(1_000.0);
        assertThat(SimLedger.realizedBeforeOpen(fills, D.plusDays(4))).isEqualTo(1_000.0 - 1_000.0);
    }

    @Test
    void 收盘权益_持仓按当天收盘_停牌用之前最近一根() {
        List<SimLedger.Fill> fills = List.of(
                fill(1, 0, 1, 100, 100, null, null, false),
                fill(2, 1, 1, 50, 200, 2, 45.0, false));
        NavigableMap<LocalDate, Double> c1 = new TreeMap<>(Map.of(D.plusDays(1), 101.0, D.plusDays(2), 103.0));
        Map<Long, NavigableMap<LocalDate, Double>> closes = Map.of(1L, c1);

        SimLedger.Equity e1 = SimLedger.equityAtClose(fills, D.plusDays(1), 100_000, closes);
        assertThat(e1.cash()).isEqualTo(80_000);
        assertThat(e1.open()).isEqualTo(2);     // 第 2 笔当天没有收盘价记录 → 按入场价
        assertThat(e1.total()).isEqualTo(80_000 + 10_100 + 10_000);

        SimLedger.Equity e3 = SimLedger.equityAtClose(fills, D.plusDays(3), 100_000, closes);   // 第 3 天停牌
        assertThat(e3.cash()).isEqualTo(80_000 + 9_000);
        assertThat(e3.positions()).isEqualTo(10_300);
        assertThat(e3.open()).isEqualTo(1);
    }

    @Test
    void 最大回撤() {
        assertThat(SimLedger.maxDrawdown(List.of(100.0, 110.0, 99.0, 120.0, 108.0))).isCloseTo(99.0 / 110 - 1, within(1e-12));
        assertThat(SimLedger.maxDrawdown(List.of(100.0))).isZero();
    }
}
