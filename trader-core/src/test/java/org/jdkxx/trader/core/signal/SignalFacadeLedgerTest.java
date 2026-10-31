package org.jdkxx.trader.core.signal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jdkxx.trader.storage.signal.AiAnalysisRepository;
import org.jdkxx.trader.storage.signal.EntrySignalRepository;
import org.jdkxx.trader.storage.signal.EntrySignalRow;
import org.jdkxx.trader.storage.signal.SignalTrackRepository;
import org.jdkxx.trader.storage.signal.SignalTrackRow;
import org.junit.jupiter.api.Test;

/**
 * Ledger grouped by model verdict.
 *
 * <p>Guard: budget skips, failures, refusals and truncations also write an analysis row attached to
 * the signal (verdict ABSENT). 3.0.0 only checked whether aiAnalysisId was null and counted all of
 * these as "allowed" - on quadruple witching (2026-09-18) 31 signals without a model verdict were
 * counted as ALLOW, distorting the comparison of whether the AI veto helps.
 */
class SignalFacadeLedgerTest {

  private static final LocalDate DAY = LocalDate.of(2026, 9, 18);

  @Test
  void 按挂着的那次分析的裁决分组() {
    EntrySignalRepository signals = mock(EntrySignalRepository.class);
    SignalTrackRepository tracks = mock(SignalTrackRepository.class);
    AiAnalysisRepository analyses = mock(AiAnalysisRepository.class);

    when(signals.findAll(any()))
        .thenReturn(
            List.of(
                signal(1, 101L, "NEW"), // model called and allowed
                signal(2, 102L, "VETOED"), // vetoed
                signal(3, 103L, "NEW"), // skipped for budget
                signal(4, 104L, "NEW"), // call failed
                signal(5, null, "NEW"))); // no analysis attached (back-fill, not enabled)
    when(tracks.list(null, null))
        .thenReturn(List.of(track(1), track(2), track(3), track(4), track(5)));
    when(analyses.verdicts(any()))
        .thenReturn(Map.of(101L, "ALLOW", 102L, "VETO", 103L, "ABSENT", 104L, "ABSENT"));

    SignalFacade facade =
        new SignalFacade(null, null, null, null, signals, tracks, null, null, null, analyses);
    Map<String, Integer> byAi =
        facade.ledger(null, null).stats().stream()
            .collect(
                Collectors.toMap(SignalFacade.LedgerStats::ai, SignalFacade.LedgerStats::total));

    assertThat(byAi).containsExactlyInAnyOrderEntriesOf(Map.of("ALLOW", 1, "VETO", 1, "NONE", 3));
  }

  private static EntrySignalRow signal(long id, Long aiAnalysisId, String status) {
    return new EntrySignalRow(
        id,
        id,
        "S" + id,
        DAY,
        "sentinel-v1",
        "UNIVERSE",
        "LIVE",
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
        aiAnalysisId,
        null,
        status,
        DAY.plusDays(3),
        null,
        null,
        null,
        null);
  }

  private static SignalTrackRow track(long signalId) {
    return new SignalTrackRow(
        signalId,
        "BASE",
        "PENDING_ENTRY",
        null,
        null,
        null,
        null,
        false,
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
        DAY,
        null);
  }
}
