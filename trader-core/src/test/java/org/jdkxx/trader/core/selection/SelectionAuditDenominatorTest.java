package org.jdkxx.trader.core.selection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jdkxx.trader.core.marketdata.audit.BarAuditService;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.SecurityType;
import org.jdkxx.trader.domain.selection.SelectionModel;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.jdkxx.trader.storage.selection.SelectionEarningsRepository;
import org.jdkxx.trader.storage.selection.SelectionPriceSeamRepository;
import org.jdkxx.trader.storage.selection.SelectionScoreRepository;
import org.jdkxx.trader.storage.selection.SelectionValuationRepository;
import org.junit.jupiter.api.Test;

/**
 * Regression (found in the 2026-10-31 review): ratedShare and valuationFresh used today's universe
 * as the denominator even when auditing a past date, so a universe change after d made a healthy
 * day fail (or a broken one pass). The denominator is now the set actually scored on d.
 */
class SelectionAuditDenominatorTest {

  private static final LocalDate D = LocalDate.of(2026, 10, 9);

  private final SelectionTargets targets = mock(SelectionTargets.class);
  private final SelectionScoreRepository scores = mock(SelectionScoreRepository.class);
  private final SelectionValuationRepository valuations = mock(SelectionValuationRepository.class);
  private final SelectionPriceSeamRepository seams = mock(SelectionPriceSeamRepository.class);
  private final SelectionAuditService audit =
      new SelectionAuditService(
          targets,
          scores,
          valuations,
          seams,
          mock(SelectionSimService.class),
          mock(SelectionEarningsRepository.class),
          () -> D,
          Clock.systemUTC());

  private static List<InstrumentRow> universe(int n) {
    List<InstrumentRow> out = new ArrayList<>();
    for (long id = 1; id <= n; id++) {
      out.add(
          new InstrumentRow(
              id,
              Market.US,
              "S" + id,
              "S" + id,
              null,
              SecurityType.STOCK,
              1,
              null,
              false,
              null,
              null,
              "RESOLVED"));
    }
    return out;
  }

  private BarAuditService.Check check(BarAuditService.Report r, String name) {
    return r.checks().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
  }

  @Test
  void 比例的分母是当天实际评分的集合而不是今天的范围() {
    // Today's universe is 500; on d 400 were scored, 380 rated (95%), 370 of them valued (92.5%)
    when(targets.all()).thenReturn(universe(500));
    when(scores.dayStats(SelectionModel.VERSION, D))
        .thenReturn(new SelectionScoreRepository.DayStats(400, 380, Map.of(), Map.of(), Map.of()));
    when(valuations.countScoredOn(SelectionModel.VERSION, D)).thenReturn(370L);
    when(seams.summary()).thenReturn(new SelectionPriceSeamRepository.Summary(0, 0));

    BarAuditService.Report r = audit.audit(D);

    BarAuditService.Check rated = check(r, "ratedShare");
    assertThat(rated.ok()).isTrue();
    assertThat(rated.detail()).contains("380/400").contains("500");
    BarAuditService.Check valued = check(r, "valuationFresh");
    assertThat(valued.ok()).isTrue();
    assertThat(valued.detail()).contains("370/400").contains("500");
  }

  @Test
  void 当天评分集合里估值不足仍然失败() {
    // 450 valuations on d, but only 300 of them belong to the 400 scored names (75%)
    when(targets.all()).thenReturn(universe(400));
    when(scores.dayStats(SelectionModel.VERSION, D))
        .thenReturn(new SelectionScoreRepository.DayStats(400, 380, Map.of(), Map.of(), Map.of()));
    when(valuations.countScoredOn(SelectionModel.VERSION, D)).thenReturn(300L);
    when(seams.summary()).thenReturn(new SelectionPriceSeamRepository.Summary(0, 0));

    BarAuditService.Check valued = check(audit.audit(D), "valuationFresh");
    assertThat(valued.ok()).isFalse();
    assertThat(valued.detail()).contains("300/400");
  }
}
