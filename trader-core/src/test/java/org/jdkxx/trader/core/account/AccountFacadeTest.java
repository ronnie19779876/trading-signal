package org.jdkxx.trader.core.account;

import org.jdkxx.trader.core.marketdata.Jobs;
import org.jdkxx.trader.core.marketdata.jobs.JobService;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.storage.account.AccountSnapshotRepository;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AccountFacadeTest {

    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final Clock AT_18_ET = Clock.fixed(Instant.parse("2026-09-14T22:00:00Z"), ZoneOffset.UTC);
    private static final Clock AT_11_ET = Clock.fixed(Instant.parse("2026-09-14T15:00:00Z"), ZoneOffset.UTC);

    private final JobService jobs = mock(JobService.class);
    private final TradingDayRepository days = mock(TradingDayRepository.class);

    private AccountFacade facade(String environment, Clock clock) {
        when(days.isTradingDay(eq(Market.US), any())).thenAnswer(inv -> LocalDate.of(2026, 9, 14).equals(inv.getArgument(1)));
        return new AccountFacade(jobs, mock(AccountSnapshotService.class), mock(AccountSnapshotRepository.class), days,
                environment, clock, ET);
    }

    @Test
    void 窗口外直接拒绝_不提交注定失败的作业() {
        assertThatThrownBy(() -> facade("PROD", AT_11_ET).snapshot("MANUAL", false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("快照窗口");
        verifyNoInteractions(jobs);
    }

    @Test
    void 窗口内提交作业() {
        when(jobs.submit(eq(Jobs.ACCOUNT_SNAPSHOT), eq("MANUAL"), any())).thenReturn(9L);

        assertThat(facade("PROD", AT_18_ET).snapshot("MANUAL", false)).isEqualTo(9L);
    }

    private static org.jdkxx.trader.storage.account.AccountSnapshotRow snapshot(LocalDate date, String nav) {
        return new org.jdkxx.trader.storage.account.AccountSnapshotRow(1, "IBKR", "k", "AC*****", date, Instant.EPOCH, "USD",
                new java.math.BigDecimal(nav), null, null, null, null, null, null, null, null, null, null, 0, "OK", "[]", null);
    }

    private static org.jdkxx.trader.storage.account.PositionSnapshotRow position(String ref, String qty, String price) {
        return new org.jdkxx.trader.storage.account.PositionSnapshotRow(ref, ref, 1L, "STK", "USD", "NYSE",
                new java.math.BigDecimal(qty), null, price == null ? null : new java.math.BigDecimal(price),
                price == null ? "NONE" : "BAR", null, null, null, false);
    }

    @Test
    void 日变化_净值差与上一份数量乘价差_持仓不变时不标近似() {
        AccountFacade.DailyChange c = AccountFacade.change(
                snapshot(LocalDate.of(2026, 9, 11), "10000"), java.util.List.of(position("A", "10", "100"), position("B", "5", "20")),
                snapshot(LocalDate.of(2026, 9, 14), "10060"), java.util.List.of(position("A", "10", "105"), position("B", "5", "22")));

        assertThat(c.previousDate()).isEqualTo(LocalDate.of(2026, 9, 11));
        assertThat(c.netLiquidationChange()).isEqualByComparingTo("60");
        assertThat(c.positionPnl()).isEqualByComparingTo("60");   // 10×5 + 5×2
        assertThat(c.positionsChanged()).isFalse();
        assertThat(c.excludedPositions()).isZero();
    }

    /**
     * 数量变过的不计入：拆股/合股与买卖在快照里长得一样，而拆股当天数量与价格同时按比例变。
     * 原先算的是「上一份数量 × 价差」，A 从 10 股变 8 股仍按 10 股乘价差记 50——
     * 卖掉的那 2 股的价差也算进去了，而拆股时错得更离谱（见下一条）。
     */
    @Test
    void 日变化_数量变过与缺价的都不计_并报出排除条数() {
        AccountFacade.DailyChange c = AccountFacade.change(
                snapshot(LocalDate.of(2026, 9, 11), "10000"), java.util.List.of(position("A", "10", "100"), position("B", "5", "20")),
                snapshot(LocalDate.of(2026, 9, 14), "9000"), java.util.List.of(position("A", "8", "105"), position("B", "5", null),
                        position("C", "1", "50")));

        assertThat(c.positionPnl()).as("A 数量变过、B 缺价、C 是新开的，一条都算不了").isEqualByComparingTo("0");
        assertThat(c.positionsChanged()).isTrue();
        assertThat(c.excludedPositions()).as("A 与 C").isEqualTo(2);
    }

    /**
     * 拆股当天不能给出量级级别的错数（2026-09-25 全项目审查发现）。
     * 3:1 拆股：10 股 @300 → 30 股 @100，市值没变。
     * 旧算法给 10 × (100 − 300) = <b>−2000</b>，还标成「当天有买卖」，把拆股误导成交易。
     */
    @Test
    void 日变化_拆股当天不再给出量级错数() {
        AccountFacade.DailyChange c = AccountFacade.change(
                snapshot(LocalDate.of(2026, 9, 11), "3000"), java.util.List.of(position("A", "10", "300")),
                snapshot(LocalDate.of(2026, 9, 14), "3000"), java.util.List.of(position("A", "30", "100")));

        assertThat(c.netLiquidationChange()).as("净值本来就没变").isEqualByComparingTo("0");
        assertThat(c.positionPnl()).as("不是 -2000").isEqualByComparingTo("0");
        assertThat(c.positionsChanged()).isTrue();
        assertThat(c.excludedPositions()).isEqualTo(1);
    }

    @Test
    void force只允许开发环境() {
        assertThatThrownBy(() -> facade("PROD", AT_18_ET).snapshot("MANUAL", true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("开发环境");
        verifyNoInteractions(jobs);

        when(jobs.submit(eq(Jobs.ACCOUNT_SNAPSHOT), eq("MANUAL"), any())).thenReturn(5L);
        assertThat(facade("DEV", AT_11_ET).snapshot("MANUAL", true)).as("开发环境窗口外也能拍").isEqualTo(5L);
    }
}
