package org.jdkxx.trader.core.marketdata.valuation;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 已存方案回读时，现值折算到哪一天。
 *
 * <p>3.1.1 前用的是「库里冻结的 asOf」配「今天的现价」：折现期从旧基准日算起、涨跌幅却拿今天的收盘比，
 * 两个日期对不上，方案存得越久偏差越大（2026-09-25 全项目审查发现）。
 */
class SotpValuationDateTest {

    @Test
    void 现价更新到今天就折算到今天() {
        assertThat(SotpService.valuationDate(LocalDate.of(2026, 1, 2), LocalDate.of(2026, 9, 24), 2030))
                .isEqualTo(LocalDate.of(2026, 9, 24));
    }

    @Test
    void 现价还停在基准日之前就用基准日() {
        assertThat(SotpService.valuationDate(LocalDate.of(2026, 9, 24), LocalDate.of(2026, 9, 11), 2030))
                .isEqualTo(LocalDate.of(2026, 9, 24));
        assertThat(SotpService.valuationDate(LocalDate.of(2026, 9, 24), null, 2030))
                .isEqualTo(LocalDate.of(2026, 9, 24));
    }

    /** 现价日已经跨过目标年时退回 asOf：SotpAssumptions 不接受目标年早于基准日所在年。 */
    @Test
    void 现价日跨过目标年就退回基准日() {
        assertThat(SotpService.valuationDate(LocalDate.of(2026, 1, 2), LocalDate.of(2031, 3, 1), 2030))
                .isEqualTo(LocalDate.of(2026, 1, 2));
        assertThat(SotpService.valuationDate(LocalDate.of(2026, 1, 2), LocalDate.of(2030, 12, 31), 2030))
                .as("同一年内照常前移").isEqualTo(LocalDate.of(2030, 12, 31));
    }
}
