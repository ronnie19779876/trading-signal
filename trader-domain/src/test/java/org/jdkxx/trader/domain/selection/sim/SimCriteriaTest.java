package org.jdkxx.trader.domain.selection.sim;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SimCriteriaTest {

    private static final LocalDate START = LocalDate.of(2026, 10, 13);
    private static final double CAP = 500_000;

    @Test
    void 还没有入场_未开始() {
        SimCriteria c = SimCriteria.evaluate(null, LocalDate.of(2026, 10, 20), CAP, null, null);
        assertThat(c.verdict()).isEqualTo(SimCriteria.Verdict.NOT_STARTED);
        assertThat(c.annualized()).isNull();
    }

    @Test
    void 入场当天无法年化() {
        SimCriteria c = SimCriteria.evaluate(START, START, CAP, 501_000.0, 0.0);
        assertThat(c.days()).isZero();
        assertThat(c.accountReturn()).isCloseTo(0.002, within(1e-12));
        assertThat(c.annualized()).isNull();
        assertThat(c.verdict()).isEqualTo(SimCriteria.Verdict.OBSERVING);
    }

    /** 方案里举的例子：第 5 天赚 1%，年化约 107%——照算照显示，结论仍是观察中。 */
    @Test
    void 未满12个月_前两条照算_结论观察中() {
        SimCriteria c = SimCriteria.evaluate(START, START.plusDays(5), CAP, 505_000.0, 0.002);
        assertThat(c.annualized()).isCloseTo(Math.pow(1.01, 73) - 1, within(1e-9));
        assertThat(c.annualized()).isGreaterThan(1.0);
        assertThat(c.annualizedMet()).isTrue();
        assertThat(c.beatSpy()).isTrue();
        assertThat(c.verdict()).isEqualTo(SimCriteria.Verdict.OBSERVING);
    }

    @Test
    void 第364天仍在观察_满一年那天开始判定() {
        LocalDate oneYear = START.plusYears(1);
        assertThat(SimCriteria.evaluate(START, oneYear.minusDays(1), CAP, 540_000.0, 0.05).verdict())
                .isEqualTo(SimCriteria.Verdict.OBSERVING);
        SimCriteria c = SimCriteria.evaluate(START, oneYear, CAP, 540_000.0, 0.05);
        assertThat(c.observationMet()).isTrue();
        assertThat(c.observationEnds()).isEqualTo(oneYear);
        assertThat(c.verdict()).isEqualTo(SimCriteria.Verdict.PASS);
    }

    /** 跨闰年的一年是 366 天：按日历年判满期，不按 365 天。 */
    @Test
    void 跨闰日按日历年满期() {
        LocalDate s = LocalDate.of(2027, 10, 13);
        SimCriteria day365 = SimCriteria.evaluate(s, s.plusDays(365), CAP, 540_000.0, 0.0);
        assertThat(day365.days()).isEqualTo(365);
        assertThat(day365.observationMet()).as("2028-02-29 在区间里，满一年是第 366 天").isFalse();
        assertThat(SimCriteria.evaluate(s, s.plusDays(366), CAP, 540_000.0, 0.0).observationMet()).isTrue();
    }

    @Test
    void 年化恰好6_算及格() {
        SimCriteria c = SimCriteria.evaluate(START, START.plusDays(365), CAP, 530_000.0, 0.0);
        assertThat(c.annualized()).isCloseTo(0.06, within(1e-12));
        assertThat(c.annualizedMet()).isTrue();
    }

    @Test
    void 年化不到6_不及格() {
        SimCriteria c = SimCriteria.evaluate(START, START.plusYears(1), CAP, 529_000.0, 0.0);
        assertThat(c.annualizedMet()).isFalse();
        assertThat(c.verdict()).isEqualTo(SimCriteria.Verdict.FAIL);
    }

    @Test
    void 与SPY打平不算跑赢() {
        SimCriteria c = SimCriteria.evaluate(START, START.plusYears(1), CAP, 550_000.0, 0.10);
        assertThat(c.annualizedMet()).isTrue();
        assertThat(c.beatSpy()).isFalse();
        assertThat(c.verdict()).isEqualTo(SimCriteria.Verdict.FAIL);
    }

    /** 过去十年 SPY 年化 13.8%：年化 7% 过了第一条，但跑输大盘，不算稳定盈利。 */
    @Test
    void 过了6但跑输大盘_不达标() {
        SimCriteria c = SimCriteria.evaluate(START, START.plusYears(1), CAP, 535_000.0, 0.138);
        assertThat(c.annualizedMet()).isTrue();
        assertThat(c.beatSpy()).isFalse();
        assertThat(c.verdict()).isEqualTo(SimCriteria.Verdict.FAIL);
    }

    @Test
    void 满一年但缺SPY数据_判断不了() {
        SimCriteria c = SimCriteria.evaluate(START, START.plusYears(1), CAP, 550_000.0, null);
        assertThat(c.beatSpy()).isNull();
        assertThat(c.verdict()).isEqualTo(SimCriteria.Verdict.UNDETERMINED);
    }
}
