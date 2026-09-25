package org.jdkxx.trader.core.signal;

import org.jdkxx.trader.core.marketdata.InstrumentDirectory;
import org.jdkxx.trader.storage.marketdata.DailyBarRepository;
import org.jdkxx.trader.storage.marketdata.RehabFactorRepository;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;
import org.jdkxx.trader.storage.signal.EntrySignalRepository;
import org.jdkxx.trader.storage.signal.SignalTrackRepository;
import org.jdkxx.trader.storage.signal.SignalTrackRow;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 纸面账本只往前算，不往回退。
 *
 * <p>补跑（BACKFILL）传进来的是<b>补跑那天</b>，而未平仓账本平时按最新交易日在算。
 * 3.1.1 前无条件重算：一次对 09-17 的补跑会把所有未平仓条目整体回退到 09-17，
 * 把 09-18 到今天之间的持有过程、甚至已经发生的平仓一起抹掉，{@code updatedThrough} 也退回去。
 * 而这条路径每晚 22:00 的补偿检查都可能走到（2026-09-25 全项目审查发现）。
 */
class SignalLedgerServiceTest {

    private final EntrySignalRepository signals = mock(EntrySignalRepository.class);
    private final SignalTrackRepository tracks = mock(SignalTrackRepository.class);
    private final InstrumentDirectory directory = mock(InstrumentDirectory.class);
    private final DailyBarRepository bars = mock(DailyBarRepository.class);
    private final RehabFactorRepository rehabs = mock(RehabFactorRepository.class);
    private final TradingDayRepository days = mock(TradingDayRepository.class);

    private SignalLedgerService service() {
        return new SignalLedgerService(signals, tracks, directory, bars, rehabs, days);
    }

    private static SignalTrackRow track(long signalId, LocalDate updatedThrough) {
        return new SignalTrackRow(signalId, "BASE", "OPEN", null, null, null, null, false, null, null, null, null, null,
                null, null, null, updatedThrough, null);
    }

    /** 要害：补跑到一个更早的日期时，已经算到更晚的条目一条都不能动。 */
    @Test
    void 补跑到更早的日期时_已经算到更晚的条目不动() {
        when(tracks.unfinished()).thenReturn(List.of(
                track(1, LocalDate.of(2026, 9, 24)),        // 已经算到 09-24
                track(2, LocalDate.of(2026, 9, 24))));

        SignalLedgerService.Summary s = service().update(LocalDate.of(2026, 9, 17));

        assertThat(s.updated()).isZero();
        verify(tracks, never()).update(any());
        verify(signals, never()).find(org.mockito.ArgumentMatchers.anyLong());
        verify(bars, never()).find(any(), org.mockito.ArgumentMatchers.anyLong(), any(), any());
    }

    /** 还没算到那天的照常重算——补跑对"落后的"条目仍然有用。 */
    @Test
    void 落后的条目照常重算() {
        when(tracks.unfinished()).thenReturn(List.of(
                track(1, LocalDate.of(2026, 9, 10)),        // 落后，要算
                track(2, LocalDate.of(2026, 9, 24)),        // 已经更新，跳过
                track(3, null)));                            // 从没算过，要算
        when(signals.find(org.mockito.ArgumentMatchers.anyLong())).thenReturn(java.util.Optional.empty());

        service().update(LocalDate.of(2026, 9, 17));

        // signals.find 抛 NoSuchElement 被 catch 记 failed，这里只验证"哪些条目进了循环"
        verify(signals).find(1L);
        verify(signals).find(3L);
        verify(signals, never()).find(2L);
    }

    /** 边界：正好等于已算到的日期也不重算（没有新数据，重算只是浪费）。 */
    @Test
    void 正好等于已算到的日期不重算() {
        when(tracks.unfinished()).thenReturn(List.of(track(1, LocalDate.of(2026, 9, 17))));

        assertThat(service().update(LocalDate.of(2026, 9, 17)).updated()).isZero();
        verify(tracks, never()).update(any());
    }
}
