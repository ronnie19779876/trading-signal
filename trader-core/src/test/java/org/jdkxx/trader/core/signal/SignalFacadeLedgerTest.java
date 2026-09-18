package org.jdkxx.trader.core.signal;

import org.jdkxx.trader.storage.signal.AiAnalysisRepository;
import org.jdkxx.trader.storage.signal.EntrySignalRepository;
import org.jdkxx.trader.storage.signal.EntrySignalRow;
import org.jdkxx.trader.storage.signal.SignalTrackRepository;
import org.jdkxx.trader.storage.signal.SignalTrackRow;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 账本按模型裁决分组。
 *
 * <p>守护：预算跳过、失败、拒答、截断也会写一行分析并挂到信号上（裁决 ABSENT）。3.0.0 只看 aiAnalysisId 是否为空，
 * 把这些都算成了"放行"——四巫日（2026-09-18）31 条没有模型结论的信号被算进 ALLOW，AI 否决有没有用的对比因此失真。
 */
class SignalFacadeLedgerTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 18);

    @Test
    void 按挂着的那次分析的裁决分组() {
        EntrySignalRepository signals = mock(EntrySignalRepository.class);
        SignalTrackRepository tracks = mock(SignalTrackRepository.class);
        AiAnalysisRepository analyses = mock(AiAnalysisRepository.class);

        when(signals.findAll(any())).thenReturn(List.of(
                signal(1, 101L, "NEW"),       // 调过模型并放行
                signal(2, 102L, "VETOED"),    // 被否决
                signal(3, 103L, "NEW"),       // 预算跳过
                signal(4, 104L, "NEW"),       // 调用失败
                signal(5, null, "NEW")));     // 没挂分析（补跑、未开启）
        when(tracks.list(null, null)).thenReturn(List.of(track(1), track(2), track(3), track(4), track(5)));
        when(analyses.verdicts(any())).thenReturn(Map.of(101L, "ALLOW", 102L, "VETO", 103L, "ABSENT", 104L, "ABSENT"));

        SignalFacade facade = new SignalFacade(null, null, null, null, signals, tracks, null, null, null, analyses);
        Map<String, Integer> byAi = facade.ledger(null, null).stats().stream()
                .collect(Collectors.toMap(SignalFacade.LedgerStats::ai, SignalFacade.LedgerStats::total));

        assertThat(byAi).containsExactlyInAnyOrderEntriesOf(Map.of("ALLOW", 1, "VETO", 1, "NONE", 3));
    }

    private static EntrySignalRow signal(long id, Long aiAnalysisId, String status) {
        return new EntrySignalRow(id, id, "S" + id, DAY, "sentinel-v1", "UNIVERSE", "LIVE", null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, aiAnalysisId, null, status, DAY.plusDays(3), null, null, null, null);
    }

    private static SignalTrackRow track(long signalId) {
        return new SignalTrackRow(signalId, "BASE", "PENDING_ENTRY", null, null, null, null, false, null, null, null, null, null,
                null, null, null, DAY, null);
    }
}
