package org.jdkxx.trader.domain.selection.sim;

import org.jdkxx.trader.domain.signal.SignalBar;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SimPathTest {

    private static final SimRules RULES = SimRules.V1;
    /** 评分日 */
    private static final LocalDate D = LocalDate.of(2026, 9, 1);

    /** 评分日之前 30 根平稳 K 线（每根高低差 2，ATR14 = 2），评分日收盘 100；之后的 K 线由各测试给。 */
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
        SimPath.Path p = SimPath.walk(with(bar(1, 95.5, 97, 94, 96)), D, D.plusDays(1), 96, null, null, RULES);
        assertThat(p.state()).isEqualTo(SimPath.State.CANCELLED);
        assertThat(p.cancelReason()).contains("95.5").contains("96");
    }

    @Test
    void 入场取日期不早于计划日的第一根开盘() {
        // 计划日 D+3 是周末之类没有 K 线的日子：顺延到 D+5 那根
        SimPath.Path p = SimPath.walk(with(bar(1, 101, 102, 100, 101), bar(5, 102, 103, 101, 102)), D, D.plusDays(3), 96, null, null, RULES);
        assertThat(p.state()).isEqualTo(SimPath.State.OPEN);
        assertThat(p.entryDate()).isEqualTo(D.plusDays(5));
        assertThat(p.entry()).isEqualTo(102.0);
    }

    /** 跳空跌穿止损：按当天收盘记账，不假装在止损价成交。 */
    @Test
    void 收盘跌破止损按收盘平仓_跳空照实记() {
        SimPath.Path p = SimPath.walk(with(bar(1, 100, 101, 99, 100), bar(2, 92, 93, 90, 91)), D, D.plusDays(1), 96, null, null, RULES);
        assertThat(p.state()).isEqualTo(SimPath.State.CLOSED);
        assertThat(p.reason()).isEqualTo(SimPath.ExitReason.STOP);
        assertThat(p.exit()).isEqualTo(91.0);
        assertThat(p.exitDate()).isEqualTo(D.plusDays(2));
        assertThat(p.barsHeld()).isEqualTo(2);
        assertThat(p.closes()).containsKeys(D.plusDays(1), D.plusDays(2));
    }

    @Test
    void 盘中跌破止损但收盘收回_不平仓() {
        SimPath.Path p = SimPath.walk(with(bar(1, 100, 101, 99, 100), bar(2, 99, 100, 94, 97)), D, D.plusDays(1), 96, null, null, RULES);
        assertThat(p.state()).isEqualTo(SimPath.State.OPEN);
        assertThat(p.lowest()).isEqualTo(94.0);
    }

    /** 入场 100、止损 96 → R = 4、+1R = 104。第 2 天最高 105 触及，吊灯 = 105 − 3×ATR 上移到初始止损之上；第 3 天收盘跌破它。 */
    @Test
    void 触及加1R后吊灯止损上移_跌破按吊灯平仓() {
        SimPath.Path p = SimPath.walk(with(bar(1, 100, 101, 99, 100), bar(2, 101, 105, 99, 104), bar(3, 103, 103, 97, 98)),
                D, D.plusDays(1), 96, null, null, RULES);
        assertThat(p.plusOneR()).isEqualTo(104.0);
        assertThat(p.touchedPlusOneR()).isTrue();
        assertThat(p.chandelierActive()).isTrue();
        assertThat(p.activeStop()).isGreaterThan(96.0).isLessThan(98.5);
        assertThat(p.reason()).isEqualTo(SimPath.ExitReason.CHANDELIER);
        assertThat(p.exit()).isEqualTo(98.0);
    }

    @Test
    void 没摸到加1R时吊灯不生效() {
        SimPath.Path p = SimPath.walk(with(bar(1, 100, 101, 99, 100), bar(2, 101, 103.9, 99, 103), bar(3, 102, 102, 96.5, 97)),
                D, D.plusDays(1), 96, null, null, RULES);
        assertThat(p.touchedPlusOneR()).isFalse();
        assertThat(p.state()).isEqualTo(SimPath.State.OPEN);
        assertThat(p.activeStop()).isEqualTo(96.0);
    }

    @Test
    void 有压力区且在入场之上_目标取区底() {
        SimPath.Path p = SimPath.walk(with(bar(1, 100, 101, 99, 100)), D, D.plusDays(1), 96, 106.0, null, RULES);
        assertThat(p.target()).isEqualTo(106.0);
        assertThat(p.targetSource()).isEqualTo(SimPath.TargetSource.RESISTANCE);
    }

    @Test
    void 没有压力区_目标是入场加2R() {
        SimPath.Path p = SimPath.walk(with(bar(1, 100, 101, 99, 100)), D, D.plusDays(1), 96, null, null, RULES);
        assertThat(p.target()).isEqualTo(108.0);
        assertThat(p.targetSource()).isEqualTo(SimPath.TargetSource.FALLBACK_2R);
    }

    @Test
    void 开盘已越过压力区_目标改用加2R() {
        SimPath.Path p = SimPath.walk(with(bar(1, 102, 103, 101, 102)), D, D.plusDays(1), 96, 101.0, null, RULES);
        assertThat(p.targetSource()).isEqualTo(SimPath.TargetSource.FALLBACK_2R);
        assertThat(p.target()).isCloseTo(102 + 2 * 6.0, within(1e-12));
    }

    @Test
    void 止盈只记触及日不自动平仓() {
        SimPath.Path p = SimPath.walk(with(bar(1, 100, 101, 99, 100), bar(2, 101, 109, 100, 108), bar(3, 108, 110, 107, 109)),
                D, D.plusDays(1), 96, null, null, RULES);
        assertThat(p.targetHitDate()).isEqualTo(D.plusDays(2));
        assertThat(p.state()).isEqualTo(SimPath.State.OPEN);
        assertThat(p.lastClose()).isEqualTo(109.0);
    }

    @Test
    void 手动平仓按请求之后的第一个开盘成交() {
        SimPath.Path p = SimPath.walk(with(bar(1, 100, 101, 99, 100), bar(2, 101, 109, 100, 108), bar(3, 107, 110, 106, 109)),
                D, D.plusDays(1), 96, null, D.plusDays(3), RULES);
        assertThat(p.state()).isEqualTo(SimPath.State.CLOSED);
        assertThat(p.reason()).isEqualTo(SimPath.ExitReason.REQUESTED);
        assertThat(p.exit()).isEqualTo(107.0);
        assertThat(p.exitDate()).isEqualTo(D.plusDays(3));
        // 开盘就平掉的那天不算持有到收盘
        assertThat(p.closes()).containsOnlyKeys(D.plusDays(1), D.plusDays(2));
    }

    /** 同一根 K 线里开盘先于收盘：请求在那天开盘成交，那天收盘跌破止损不再算。 */
    @Test
    void 平仓日开盘先成交_收盘止损不再算() {
        SimPath.Path p = SimPath.walk(with(bar(1, 100, 101, 99, 100), bar(2, 99, 99, 90, 91)), D, D.plusDays(1), 96, null, D.plusDays(2), RULES);
        assertThat(p.reason()).isEqualTo(SimPath.ExitReason.REQUESTED);
        assertThat(p.exit()).isEqualTo(99.0);
    }

    /** 止损先于请求的平仓日发生：照止损平，请求落空。 */
    @Test
    void 平仓日之前已止损_按止损() {
        SimPath.Path p = SimPath.walk(with(bar(1, 100, 101, 99, 100), bar(2, 97, 97, 94, 95), bar(3, 96, 97, 95, 96)),
                D, D.plusDays(1), 96, null, D.plusDays(3), RULES);
        assertThat(p.reason()).isEqualTo(SimPath.ExitReason.STOP);
        assertThat(p.exitDate()).isEqualTo(D.plusDays(2));
    }

    /** 请求日不晚于入场日（入场当天就点了平仓）：入场那根不平，下一根开盘平。 */
    @Test
    void 请求不晚于入场日_入场后下一根开盘平() {
        SimPath.Path p = SimPath.walk(with(bar(1, 100, 101, 99, 100), bar(2, 102, 103, 101, 102)), D, D.plusDays(1), 96, null, D.plusDays(1), RULES);
        assertThat(p.entryDate()).isEqualTo(D.plusDays(1));
        assertThat(p.exitDate()).isEqualTo(D.plusDays(2));
        assertThat(p.exit()).isEqualTo(102.0);
    }

    @Test
    void 乱序K线直接报错() {
        List<SignalBar> bars = with(bar(2, 100, 101, 99, 100), bar(1, 100, 101, 99, 100));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> SimPath.walk(bars, D, D.plusDays(1), 96, null, null, RULES))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
