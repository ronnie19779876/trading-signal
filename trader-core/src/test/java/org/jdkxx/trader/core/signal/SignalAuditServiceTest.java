package org.jdkxx.trader.core.signal;

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

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 信号审计。
 *
 * <p>重点是 signalConsistency：它存在的意义就是报告「信号还在、评估结论已经变了」，
 * 而**最可能出现的那种不一致恰好让它崩**——评估重跑成 SKIPPED 时 outcome 落库为 NULL，
 * `Set.of(...).contains(null)` 抛 NPE，整个审计 500，巡检第五段跟着挂（2026-09-25 全项目审查发现）。
 */
class SignalAuditServiceTest {

    private static final LocalDate D = LocalDate.of(2026, 9, 24);
    private static final ZoneId ZONE = ZoneId.of("America/New_York");
    /** 美东 09-24 19:30：过了 19:00 的核对时点。 */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-24T23:30:00Z"), ZONE);

    private final SignalEvaluationService evaluation = mock(SignalEvaluationService.class);
    private final SignalEvaluationRepository evaluations = mock(SignalEvaluationRepository.class);
    private final EntrySignalRepository signals = mock(EntrySignalRepository.class);
    private final SignalTrackRepository tracks = mock(SignalTrackRepository.class);
    private final TradingDayRepository days = mock(TradingDayRepository.class);
    private final JobRunRepository jobs = mock(JobRunRepository.class);
    private final AiAnalysisRepository analyses = mock(AiAnalysisRepository.class);

    private static SignalEvaluationRow evaluationRow(long id, String symbol, String status, String outcome) {
        return new SignalEvaluationRow(id, symbol, D, "sentinel-v1", status, null, outcome, "[]", 0,
                null, "POOL", null, null, null, null, null, null, "fp", null, 1L, null);
    }

    private static EntrySignalRow signalRow(long id, String symbol) {
        return new EntrySignalRow(id, id, symbol, D, "sentinel-v1", "POOL", "DAILY", null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, "NEW", D.plusDays(3), null, null, 1L, null);
    }

    private BarAuditService.Report audit(List<SignalEvaluationRow> rows, List<EntrySignalRow> daySignals) {
        return audit(rows, daySignals, java.util.Map.of());
    }

    private BarAuditService.Report audit(List<SignalEvaluationRow> rows, List<EntrySignalRow> daySignals,
                                         java.util.Map<Long, InstrumentRow> targetsOnD) {
        when(days.covers(any(), any())).thenReturn(true);
        when(days.isTradingDay(Market.US, D)).thenReturn(true);
        when(evaluation.targetsOn(D)).thenReturn(targetsOnD);
        when(evaluations.on(D, "sentinel-v1")).thenReturn(rows);
        when(signals.on(D, "sentinel-v1")).thenReturn(daySignals);
        when(analyses.onTradeDate(D)).thenReturn(List.of());
        when(tracks.staleUnfinished(D)).thenReturn(0L);
        when(jobs.latestOf(any())).thenReturn(Optional.empty());
        return new SignalAuditService(evaluation, evaluations, signals, tracks, days, jobs, analyses, CLOCK, ZONE).audit(D);
    }

    private static BarAuditService.Check check(BarAuditService.Report r, String name) {
        return r.checks().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
    }

    /** 要害：评估重跑成 SKIPPED（outcome 落库为 NULL）而信号还在，正是这条检查该报的情形。 */
    @Test
    void 评估被重跑成跳过时_报告不一致而不是抛NPE() {
        BarAuditService.Report r = audit(
                List.of(evaluationRow(1, "AAPL", "SKIPPED_STALE_DATA", null)),
                List.of(signalRow(1, "AAPL")));

        BarAuditService.Check c = check(r, "signalConsistency");
        assertThat(c.ok()).isFalse();
        assertThat(c.critical()).as("K 线被订正过就会这样，信号保留，提示即可").isFalse();
        assertThat(c.samples()).containsExactly("AAPL");
        assertThat(r.ok()).as("不是关键项，总判定仍通过").isTrue();
    }

    /** 信号对应的评估行整个不见了（评估被删或换了版本号）。 */
    @Test
    void 信号找不到对应评估时也报不一致() {
        BarAuditService.Report r = audit(
                List.of(evaluationRow(1, "AAPL", "EVALUATED", "SIGNAL")),
                List.of(signalRow(1, "AAPL"), signalRow(2, "MSFT")));

        assertThat(check(r, "signalConsistency").samples()).containsExactly("MSFT");
    }

    @Test
    void 结论是SIGNAL或BLOCKED_BY_AI时判一致() {
        BarAuditService.Report r = audit(
                List.of(evaluationRow(1, "AAPL", "EVALUATED", "SIGNAL"),
                        evaluationRow(2, "MSFT", "EVALUATED", "BLOCKED_BY_AI")),
                List.of(signalRow(1, "AAPL"), signalRow(2, "MSFT")));

        BarAuditService.Check c = check(r, "signalConsistency");
        assertThat(c.ok()).isTrue();
        assertThat(c.detail()).contains("2 条信号与当天评估结论一致");
    }

    /** NO_SIGNAL 的评估却挂着信号：同样是不一致，不能因为 outcome 非空就放过。 */
    @Test
    void 结论是NO_SIGNAL却有信号时报不一致() {
        BarAuditService.Report r = audit(
                List.of(evaluationRow(1, "AAPL", "EVALUATED", "NO_SIGNAL")),
                List.of(signalRow(1, "AAPL")));

        assertThat(check(r, "signalConsistency").samples()).containsExactly("AAPL");
    }

    private static java.util.Map<Long, InstrumentRow> targets(Object... idSymbol) {
        java.util.Map<Long, InstrumentRow> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < idSymbol.length; i += 2) {
            long id = ((Number) idSymbol[i]).longValue();
            String sym = (String) idSymbol[i + 1];
            m.put(id, new InstrumentRow(id, Market.US, sym, sym, null, SecurityType.STOCK, 1, null, false, "NYSE", id, "RESOLVED"));
        }
        return m;
    }

    /**
     * 回归（2026-10-12 生产误报）：周六成分股同步新进 3 只、退出 2 只后，周一收盘前审上周五——
     * 那天的评估是按当时的名单跑的，退出的那只有评估、新进的没有。分母必须按 d 当天有效的目标，不能用现在的名单。
     */
    @Test
    void 覆盖按当天有效的目标核对_事后退出的多评了不算错() {
        BarAuditService.Report r = audit(
                List.of(evaluationRow(1, "AAPL", "EVALUATED", "NO_SIGNAL"), evaluationRow(2, "WBD", "SKIPPED_STALE_DATA", null)),
                List.of(), targets(1, "AAPL", 2, "WBD"));
        BarAuditService.Check c = check(r, "coverage");
        assertThat(c.ok()).isTrue();
        assertThat(c.count()).isZero();
        verify(evaluation, never()).targets();
    }

    /** 真缺评估时点名缺的是哪几只（原先只给条数，要自己去比两张名单）。 */
    @Test
    void 当天的目标缺评估_关键项失败并点名() {
        BarAuditService.Report r = audit(
                List.of(evaluationRow(1, "AAPL", "EVALUATED", "NO_SIGNAL"), evaluationRow(9, "OLD", "EVALUATED", "NO_SIGNAL")),
                List.of(), targets(1, "AAPL", 2, "MSFT", 3, "NVDA"));
        BarAuditService.Check c = check(r, "coverage");
        assertThat(c.ok()).as("评估条数 2 不少于… 不能拿条数比：OLD 不是当天的目标，MSFT、NVDA 真缺").isFalse();
        assertThat(c.critical()).isTrue();
        assertThat(c.samples()).containsExactly("MSFT", "NVDA");
        assertThat(r.ok()).isFalse();
    }
}
