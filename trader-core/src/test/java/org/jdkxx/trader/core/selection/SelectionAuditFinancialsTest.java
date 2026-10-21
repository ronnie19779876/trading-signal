package org.jdkxx.trader.core.selection;

import org.jdkxx.trader.core.marketdata.audit.BarAuditService;
import org.jdkxx.trader.storage.selection.SelectionEarningsRepository;
import org.jdkxx.trader.storage.selection.SelectionPriceSeamRepository;
import org.jdkxx.trader.storage.selection.SelectionScoreRepository;
import org.jdkxx.trader.storage.selection.SelectionValuationRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 3.4.6 审计项 financialsComplete：45 天内只报续取个数，超期仍缺的逐只列出；范围外的不算。 */
class SelectionAuditFinancialsTest {

    private static final LocalDate D = LocalDate.of(2026, 10, 21);

    private final SelectionEarningsRepository earnings = mock(SelectionEarningsRepository.class);
    private final SelectionAuditService audit = new SelectionAuditService(mock(SelectionTargets.class), mock(SelectionScoreRepository.class),
            mock(SelectionValuationRepository.class), mock(SelectionPriceSeamRepository.class), mock(SelectionSimService.class),
            earnings, () -> D, Clock.systemUTC());

    private static SelectionEarningsRepository.IncompleteFinancials f(long id, String symbol, LocalDate pub, String... missing) {
        return new SelectionEarningsRepository.IncompleteFinancials(id, symbol, pub, List.of(missing));
    }

    @Test
    void 超期仍缺的逐只列出_续取中的只报个数() {
        int w = SelectionDailyService.COMPLETE_LOOKBACK_DAYS;
        when(earnings.incompleteFinancials(D.minusDays(w), D)).thenReturn(List.of(
                f(1, "JPM", D.minusDays(8), "CASH_FLOW"), f(2, "ABT", D, "BALANCE_SHEET", "CASH_FLOW")));
        when(earnings.incompleteFinancials(D.minusDays(SelectionAuditService.OVERDUE_WINDOW_DAYS), D.minusDays(w + 1L))).thenReturn(List.of(
                f(3, "ES", LocalDate.of(2026, 7, 30), "CASH_FLOW"), f(99, "OUT", LocalDate.of(2026, 7, 30), "INCOME")));

        BarAuditService.Check c = audit.financialsComplete(D, Set.of(1L, 2L, 3L));

        assertThat(c.critical()).isFalse();
        assertThat(c.ok()).isFalse();
        assertThat(c.count()).isEqualTo(1);
        assertThat(c.samples()).containsExactly("ES 2026-07-30 公告，缺 CASH_FLOW");
        assertThat(c.detail()).contains("续取中 2 只").contains("超过 45 天仍缺 1 只");
    }

    @Test
    void 都齐了就通过() {
        BarAuditService.Check c = audit.financialsComplete(D, Set.of(1L));
        assertThat(c.ok()).isTrue();
        assertThat(c.detail()).contains("都已到齐").contains("没有超期仍缺的");
    }
}
