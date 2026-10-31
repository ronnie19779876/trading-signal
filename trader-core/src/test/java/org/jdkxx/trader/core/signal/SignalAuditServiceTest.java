package org.jdkxx.trader.core.signal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.jdkxx.trader.core.marketdata.audit.BarAuditService;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.SecurityType;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.jdkxx.trader.storage.marketdata.JobRunRepository;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;
import org.jdkxx.trader.storage.signal.AiAnalysisRepository;
import org.jdkxx.trader.storage.signal.EntrySignalRepository;
import org.jdkxx.trader.storage.signal.EntrySignalRow;
import org.jdkxx.trader.storage.signal.SignalEvaluationRepository;
import org.jdkxx.trader.storage.signal.SignalEvaluationRow;
import org.jdkxx.trader.storage.signal.SignalTrackRepository;
import org.junit.jupiter.api.Test;

/**
 * Signal audit.
 *
 * <p>The focus is signalConsistency: its whole point is to report "the signal is still there but
 * the evaluation verdict has changed", and **the most likely such inconsistency is exactly what
 * used to crash it** - when an evaluation is re-run as SKIPPED its outcome is stored as NULL,
 * `Set.of(...).contains(null)` throws NPE, the whole audit returns 500 and section five of the
 * daily check dies with it (found in the 2026-09-25 full project review).
 */
class SignalAuditServiceTest {

  private static final LocalDate D = LocalDate.of(2026, 9, 24);
  private static final ZoneId ZONE = ZoneId.of("America/New_York");

  /** 09-24 19:30 US/Eastern: past the 19:00 check time. */
  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-24T23:30:00Z"), ZONE);

  private final SignalEvaluationService evaluation = mock(SignalEvaluationService.class);
  private final SignalEvaluationRepository evaluations = mock(SignalEvaluationRepository.class);
  private final EntrySignalRepository signals = mock(EntrySignalRepository.class);
  private final SignalTrackRepository tracks = mock(SignalTrackRepository.class);
  private final TradingDayRepository days = mock(TradingDayRepository.class);
  private final JobRunRepository jobs = mock(JobRunRepository.class);
  private final AiAnalysisRepository analyses = mock(AiAnalysisRepository.class);

  private static SignalEvaluationRow evaluationRow(
      long id, String symbol, String status, String outcome) {
    return new SignalEvaluationRow(
        id,
        symbol,
        D,
        "sentinel-v1",
        status,
        null,
        outcome,
        "[]",
        0,
        null,
        "POOL",
        null,
        null,
        null,
        null,
        null,
        null,
        "fp",
        null,
        1L,
        null);
  }

  private static EntrySignalRow signalRow(long id, String symbol) {
    return new EntrySignalRow(
        id,
        id,
        symbol,
        D,
        "sentinel-v1",
        "POOL",
        "DAILY",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        "NEW",
        D.plusDays(3),
        null,
        null,
        1L,
        null);
  }

  private BarAuditService.Report audit(
      List<SignalEvaluationRow> rows, List<EntrySignalRow> daySignals) {
    return audit(rows, daySignals, java.util.Map.of());
  }

  private BarAuditService.Report audit(
      List<SignalEvaluationRow> rows,
      List<EntrySignalRow> daySignals,
      java.util.Map<Long, InstrumentRow> targetsOnD) {
    when(days.covers(any(), any())).thenReturn(true);
    when(days.isTradingDay(Market.US, D)).thenReturn(true);
    when(evaluation.targetsOn(D)).thenReturn(targetsOnD);
    when(evaluations.on(D, "sentinel-v1")).thenReturn(rows);
    when(signals.on(D, "sentinel-v1")).thenReturn(daySignals);
    when(analyses.onTradeDate(D)).thenReturn(List.of());
    when(tracks.staleUnfinished(D)).thenReturn(0L);
    when(jobs.latestOf(any())).thenReturn(Optional.empty());
    return new SignalAuditService(
            evaluation, evaluations, signals, tracks, days, jobs, analyses, CLOCK, ZONE)
        .audit(D);
  }

  private static BarAuditService.Check check(BarAuditService.Report r, String name) {
    return r.checks().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
  }

  /**
   * The crux: the evaluation was re-run as SKIPPED (outcome stored as NULL) while the signal
   * remains - exactly the case this check must report.
   */
  @Test
  void 评估被重跑成跳过时_报告不一致而不是抛NPE() {
    BarAuditService.Report r =
        audit(
            List.of(evaluationRow(1, "AAPL", "SKIPPED_STALE_DATA", null)),
            List.of(signalRow(1, "AAPL")));

    BarAuditService.Check c = check(r, "signalConsistency");
    assertThat(c.ok()).isFalse();
    assertThat(c.critical()).as("K 线被订正过就会这样，信号保留，提示即可").isFalse();
    assertThat(c.samples()).containsExactly("AAPL");
    assertThat(r.ok()).as("不是关键项，总判定仍通过").isTrue();
  }

  /** The signal's evaluation row is gone entirely (evaluation deleted or version changed). */
  @Test
  void 信号找不到对应评估时也报不一致() {
    BarAuditService.Report r =
        audit(
            List.of(evaluationRow(1, "AAPL", "EVALUATED", "SIGNAL")),
            List.of(signalRow(1, "AAPL"), signalRow(2, "MSFT")));

    assertThat(check(r, "signalConsistency").samples()).containsExactly("MSFT");
  }

  @Test
  void 结论是SIGNAL或BLOCKED_BY_AI时判一致() {
    BarAuditService.Report r =
        audit(
            List.of(
                evaluationRow(1, "AAPL", "EVALUATED", "SIGNAL"),
                evaluationRow(2, "MSFT", "EVALUATED", "BLOCKED_BY_AI")),
            List.of(signalRow(1, "AAPL"), signalRow(2, "MSFT")));

    BarAuditService.Check c = check(r, "signalConsistency");
    assertThat(c.ok()).isTrue();
    assertThat(c.detail()).contains("2 条信号与当天评估结论一致");
  }

  /**
   * A NO_SIGNAL evaluation with a signal attached: just as inconsistent; a non-null outcome must
   * not let it pass.
   */
  @Test
  void 结论是NO_SIGNAL却有信号时报不一致() {
    BarAuditService.Report r =
        audit(
            List.of(evaluationRow(1, "AAPL", "EVALUATED", "NO_SIGNAL")),
            List.of(signalRow(1, "AAPL")));

    assertThat(check(r, "signalConsistency").samples()).containsExactly("AAPL");
  }

  private static java.util.Map<Long, InstrumentRow> targets(Object... idSymbol) {
    java.util.Map<Long, InstrumentRow> m = new java.util.LinkedHashMap<>();
    for (int i = 0; i < idSymbol.length; i += 2) {
      long id = ((Number) idSymbol[i]).longValue();
      String sym = (String) idSymbol[i + 1];
      m.put(
          id,
          new InstrumentRow(
              id,
              Market.US,
              sym,
              sym,
              null,
              SecurityType.STOCK,
              1,
              null,
              false,
              "NYSE",
              id,
              "RESOLVED"));
    }
    return m;
  }

  /**
   * Regression (2026-10-12 production false alarm): after Saturday's constituent sync added 3 and
   * removed 2, auditing the previous Friday before Monday's close - that day's evaluation ran on
   * the list of that time, so the removed name has an evaluation and the new ones do not. The
   * denominator must be the targets valid on day d, not the current list.
   */
  @Test
  void 覆盖按当天有效的目标核对_事后退出的多评了不算错() {
    BarAuditService.Report r =
        audit(
            List.of(
                evaluationRow(1, "AAPL", "EVALUATED", "NO_SIGNAL"),
                evaluationRow(2, "WBD", "SKIPPED_STALE_DATA", null)),
            List.of(),
            targets(1, "AAPL", 2, "WBD"));
    BarAuditService.Check c = check(r, "coverage");
    assertThat(c.ok()).isTrue();
    assertThat(c.count()).isZero();
    verify(evaluation, never()).targets();
  }

  /**
   * When evaluations really are missing, name the missing instruments (previously only the count
   * was given and you had to diff the two lists yourself).
   */
  @Test
  void 当天的目标缺评估_关键项失败并点名() {
    BarAuditService.Report r =
        audit(
            List.of(
                evaluationRow(1, "AAPL", "EVALUATED", "NO_SIGNAL"),
                evaluationRow(9, "OLD", "EVALUATED", "NO_SIGNAL")),
            List.of(),
            targets(1, "AAPL", 2, "MSFT", 3, "NVDA"));
    BarAuditService.Check c = check(r, "coverage");
    assertThat(c.ok()).as("评估条数 2 不少于… 不能拿条数比：OLD 不是当天的目标，MSFT、NVDA 真缺").isFalse();
    assertThat(c.critical()).isTrue();
    assertThat(c.samples()).containsExactly("MSFT", "NVDA");
    assertThat(r.ok()).isFalse();
  }
}
