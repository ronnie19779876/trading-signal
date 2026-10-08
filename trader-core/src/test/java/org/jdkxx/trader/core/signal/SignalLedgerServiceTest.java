package org.jdkxx.trader.core.signal;

import org.jdkxx.trader.core.marketdata.InstrumentDirectory;
import org.jdkxx.trader.storage.marketdata.DailyBarRepository;
import org.jdkxx.trader.storage.marketdata.RehabFactorRepository;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;
import org.jdkxx.trader.storage.signal.EntrySignalRepository;
import org.jdkxx.trader.storage.signal.EntrySignalRow;
import org.jdkxx.trader.storage.signal.SignalTrackRepository;
import org.jdkxx.trader.storage.signal.SignalTrackRow;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
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
                null, null, null, null, null, null, updatedThrough, null);
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

    // ------------------------------------------------------------------ 当前价与浮动盈亏（3.1.4）

    private static final org.jdkxx.trader.domain.Instrument X = org.jdkxx.trader.domain.Instrument.us("X");

    private static org.jdkxx.trader.domain.DailyBar bar(LocalDate d, double c) {
        return new org.jdkxx.trader.domain.DailyBar(X, d, java.math.BigDecimal.valueOf(c), java.math.BigDecimal.valueOf(c + 1),
                java.math.BigDecimal.valueOf(c - 1), java.math.BigDecimal.valueOf(c), null, 1000, null, null, null, null, false);
    }

    private static EntrySignalRow sig(LocalDate day, double close) {
        return new EntrySignalRow(1, 1, "X", day, "sentinel-v1", "UNIVERSE", "LIVE", java.math.BigDecimal.valueOf(close),
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "NEW",
                day.plusDays(3), null, null, null, null);
    }

    private static SignalTrackRow trackWithStop(String variant, double stop) {
        return new SignalTrackRow(1, variant, "OPEN", java.math.BigDecimal.valueOf(stop), null, null, null, false,
                null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /** 造一段行情：判定日 100，之后走到 end 的收盘价为 lastClose。 */
    private static Object[] series(LocalDate signalDay, LocalDate end, double lastClose) {
        List<org.jdkxx.trader.domain.DailyBar> raw = new java.util.ArrayList<>();
        java.util.Set<LocalDate> cal = new java.util.HashSet<>();
        for (LocalDate d = signalDay.minusDays(30); !d.isAfter(signalDay); d = d.plusDays(1)) {
            raw.add(bar(d, 100));
            cal.add(d);
        }
        for (LocalDate d = signalDay.plusDays(1); !d.isAfter(end); d = d.plusDays(1)) {
            raw.add(bar(d, d.equals(end) ? lastClose : 100));
            cal.add(d);
        }
        return new Object[] {raw, cal};
    }

    @Test
    @SuppressWarnings("unchecked")
    void 未平仓填当前价_已平仓三列留空() {
        LocalDate day = LocalDate.of(2026, 3, 2);
        LocalDate end = day.plusDays(3);

        Object[] openSeries = series(day, end, 104);        // 高于止损 → 未平仓
        SignalTrackRow open = service().recompute(sig(day, 100), trackWithStop("BASE", 95),
                (List<org.jdkxx.trader.domain.DailyBar>) openSeries[0], List.of(),
                (java.util.Set<LocalDate>) openSeries[1], end);

        assertThat(open.status()).isEqualTo("OPEN");
        assertThat(open.lastClose()).isNotNull().satisfies(v -> assertThat(v.doubleValue()).isCloseTo(104, within(1e-6)));
        assertThat(open.lastCloseDate()).isEqualTo(end);
        assertThat(open.unrealizedR()).isNotNull();

        Object[] closedSeries = series(day, end, 90);       // 跌破止损 → 已平仓
        SignalTrackRow closed = service().recompute(sig(day, 100), trackWithStop("BASE", 95),
                (List<org.jdkxx.trader.domain.DailyBar>) closedSeries[0], List.of(),
                (java.util.Set<LocalDate>) closedSeries[1], end);

        assertThat(closed.status()).isEqualTo("CLOSED");
        assertThat(closed.lastClose()).as("已平仓看 exit_price，当前价留空——不是 0").isNull();
        assertThat(closed.lastCloseDate()).isNull();
        assertThat(closed.unrealizedR()).as("已平仓看 r_multiple").isNull();
    }

    /**
     * 两个变体的当前价与浮动收益率必然相同（同入场价、同现价），但 <b>unrealized_r 不同</b>：
     * R = 判定日收盘 − 本变体止损，止损不同则 R 不同。2026-10-09 生产实录：
     * AMAT 的 R 是 43.88（BASE）对 54.10（STOP_2_5）。
     */
    @Test
    @SuppressWarnings("unchecked")
    void 两个变体当前价相同但浮动R不同() {
        LocalDate day = LocalDate.of(2026, 3, 2);
        LocalDate end = day.plusDays(3);
        Object[] s = series(day, end, 104);
        List<org.jdkxx.trader.domain.DailyBar> raw = (List<org.jdkxx.trader.domain.DailyBar>) s[0];
        java.util.Set<LocalDate> cal = (java.util.Set<LocalDate>) s[1];

        SignalTrackRow base = service().recompute(sig(day, 100), trackWithStop("BASE", 95), raw, List.of(), cal, end);
        SignalTrackRow wide = service().recompute(sig(day, 100), trackWithStop("STOP_2_5", 93.75), raw, List.of(), cal, end);

        assertThat(base.lastClose()).isEqualByComparingTo(wide.lastClose());
        assertThat(base.lastCloseDate()).isEqualTo(wide.lastCloseDate());
        // 入场 100、现价 104 → BASE: 4/5 = 0.8；STOP_2_5: 4/6.25 = 0.64
        assertThat(base.unrealizedR().doubleValue()).isCloseTo(0.8, within(1e-6));
        assertThat(wide.unrealizedR().doubleValue()).isCloseTo(0.64, within(1e-6));
        assertThat(base.unrealizedR()).isNotEqualByComparingTo(wide.unrealizedR());
    }
}
