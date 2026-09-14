package org.jdkxx.trader.core.account;

import org.jdkxx.trader.core.marketdata.audit.BarAuditService.Check;
import org.jdkxx.trader.core.marketdata.audit.BarAuditService.Report;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.storage.account.AccountSnapshotRepository;
import org.jdkxx.trader.storage.account.AccountSnapshotRow;
import org.jdkxx.trader.storage.account.PositionSnapshotRow;
import org.jdkxx.trader.storage.marketdata.JobRunRepository;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccountAuditServiceTest {

    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final LocalDate MON = LocalDate.of(2026, 9, 14);
    /** 美东周一 18:10：快照时点刚过，还没到核对时点。 */
    private static final Clock AT_1810_ET = Clock.fixed(Instant.parse("2026-09-14T22:10:00Z"), ZoneOffset.UTC);
    /** 美东周一 19:00：已过核对时点。 */
    private static final Clock AT_1900_ET = Clock.fixed(Instant.parse("2026-09-14T23:00:00Z"), ZoneOffset.UTC);

    private final AccountSnapshotRepository snapshots = mock(AccountSnapshotRepository.class);
    private final TradingDayRepository days = mock(TradingDayRepository.class);

    private AccountAuditService service(Clock clock) {
        return new AccountAuditService(snapshots, days, mock(JobRunRepository.class), clock, ET);
    }

    private static AccountSnapshotRow row(String status) {
        return new AccountSnapshotRow(1, "IBKR", "k", "AC*****", MON, Instant.EPOCH, "USD", BigDecimal.TEN, null, null, null,
                null, null, null, null, null, null, null, 1, status, "[]", 7L);
    }

    private static PositionSnapshotRow position(String symbol, String source) {
        return new PositionSnapshotRow("1", symbol, 1L, "STK", "USD", "NYSE", BigDecimal.ONE, null, null, source, null, null, null, false);
    }

    private static Check check(Report r, String name) {
        return r.checks().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
    }

    @BeforeEach
    void calendar() {
        when(days.covers(eq(Market.US), any())).thenReturn(true);
        when(days.isTradingDay(eq(Market.US), any())).thenAnswer(inv -> MON.equals(inv.getArgument(1)));
        when(days.between(eq(Market.US), any(), any())).thenReturn(List.of(LocalDate.of(2026, 9, 11), MON));
    }

    @Test
    void 核对时点前缺快照只提示不判失败() {
        Report r = service(AT_1810_ET).audit(null);

        assertThat(r.date()).isEqualTo(MON);
        assertThat(r.ok()).isTrue();
        assertThat(check(r, "snapshotExists").ok()).isFalse();
        assertThat(check(r, "snapshotExists").critical()).isFalse();
    }

    @Test
    void 从来没有过快照时缺快照只提示_刚启用不算停摆() {
        Report r = service(AT_1900_ET).audit(null);

        assertThat(r.ok()).isTrue();
        assertThat(check(r, "snapshotExists").critical()).isFalse();
        assertThat(check(r, "snapshotExists").detail()).contains("刚启用");
    }

    @Test
    void 过了核对时点仍缺快照判失败() {
        when(snapshots.latest()).thenReturn(java.util.Optional.of(row("OK")));

        Report r = service(AT_1900_ET).audit(null);

        assertThat(r.ok()).isFalse();
        assertThat(check(r, "snapshotExists").critical()).isTrue();
        assertThat(check(r, "snapshotExists").detail()).contains("补不回来");
    }

    @Test
    void 对账FAIL判失败_WARN只提示() {
        when(snapshots.on(MON)).thenReturn(List.of(row("FAIL")));
        when(snapshots.reconChecks(1)).thenReturn(List.of(new AccountSnapshotRepository.ReconCheck("identity", "FAIL", "相差 999")));
        assertThat(service(AT_1900_ET).audit(MON).ok()).isFalse();

        when(snapshots.on(MON)).thenReturn(List.of(row("WARN")));
        when(snapshots.reconChecks(1)).thenReturn(List.of(new AccountSnapshotRepository.ReconCheck("holdings", "WARN", "持有但池里没标 HOLDING [AAA]")));
        Report r = service(AT_1900_ET).audit(MON);
        assertThat(r.ok()).isTrue();
        assertThat(check(r, "reconciliation").ok()).isFalse();
        assertThat(check(r, "reconciliation").critical()).isFalse();
        assertThat(check(r, "reconciliation").samples()).singleElement().asString().contains("holdings WARN");
    }

    @Test
    void 缺价只提示并列出代码() {
        when(snapshots.on(MON)).thenReturn(List.of(row("WARN")));
        when(snapshots.positions(1)).thenReturn(List.of(position("AAA", "BAR"), position("BBB", "NONE"), position("CCC", "SNAPSHOT")));

        Report r = service(AT_1900_ET).audit(MON);

        assertThat(check(r, "pricing").ok()).isFalse();
        assertThat(check(r, "pricing").critical()).isFalse();
        assertThat(check(r, "pricing").samples()).containsExactly("BBB");
        assertThat(r.summary()).containsEntry("positions", 3);
    }

    @Test
    void 休市日直接判过() {
        Report r = service(AT_1900_ET).audit(LocalDate.of(2026, 9, 13));

        assertThat(r.ok()).isTrue();
        assertThat(r.checks()).singleElement().extracting(Check::name).isEqualTo("calendar");
    }
}
