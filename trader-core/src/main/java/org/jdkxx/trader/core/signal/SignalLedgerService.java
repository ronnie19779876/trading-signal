package org.jdkxx.trader.core.signal;

import org.jdkxx.trader.core.marketdata.InstrumentDirectory;
import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.RehabFactor;
import org.jdkxx.trader.domain.signal.PaperTrade;
import org.jdkxx.trader.domain.signal.SentinelThresholds;
import org.jdkxx.trader.domain.signal.SignalTrades;
import org.jdkxx.trader.storage.marketdata.DailyBarRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.jdkxx.trader.storage.marketdata.RehabFactorRepository;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;
import org.jdkxx.trader.storage.signal.EntrySignalRepository;
import org.jdkxx.trader.storage.signal.EntrySignalRow;
import org.jdkxx.trader.storage.signal.SignalTrackRepository;
import org.jdkxx.trader.storage.signal.SignalTrackRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 纸面跟踪账本：未平仓（含待入场）的每一行，每天从信号日起<b>整段重算</b>到截止日（{@link SignalTrades}，与回放同一入口）。
 * 不存增量状态：持有期间的拆股、K 线订正都自然体现在重算里。已平仓的不再重算。
 */
public class SignalLedgerService {

    private static final Logger log = LoggerFactory.getLogger(SignalLedgerService.class);

    public record Summary(int updated, int closed, int failed) {
    }

    private final EntrySignalRepository signals;
    private final SignalTrackRepository tracks;
    private final InstrumentDirectory directory;
    private final DailyBarRepository bars;
    private final RehabFactorRepository rehabs;
    private final TradingDayRepository days;
    private final SentinelThresholds th = SentinelThresholds.V1;

    public SignalLedgerService(EntrySignalRepository signals, SignalTrackRepository tracks, InstrumentDirectory directory,
                               DailyBarRepository bars, RehabFactorRepository rehabs, TradingDayRepository days) {
        this.signals = signals;
        this.tracks = tracks;
        this.directory = directory;
        this.bars = bars;
        this.rehabs = rehabs;
        this.days = days;
    }

    /**
     * 把未平仓的纸面账本算到 {@code through}。
     *
     * <p><b>只往前走，不往回退</b>（3.1.2 修）：已经算到更晚日期的条目跳过。
     * 补跑（BACKFILL）传进来的是<b>补跑那天</b>，而未平仓账本平时是按最新交易日在算的——
     * 原先无条件重算，一次对 09-17 的补跑会把所有未平仓条目的状态整体回退到 09-17，
     * 把 09-18~今天之间的持有过程、甚至已经发生的平仓一起抹掉（{@code updatedThrough} 也跟着退回去）；
     * 而这条路径每晚 22:00 的补偿检查都可能走到（2026-09-25 全项目审查发现）。
     */
    public Summary update(LocalDate through) {
        Map<Long, List<SignalTrackRow>> bySignal = tracks.unfinished().stream()
                .filter(t -> t.updatedThrough() == null || t.updatedThrough().isBefore(through))
                .collect(Collectors.groupingBy(SignalTrackRow::signalId, LinkedHashMap::new, Collectors.toList()));
        int updated = 0;
        int closed = 0;
        int failed = 0;
        for (Map.Entry<Long, List<SignalTrackRow>> en : bySignal.entrySet()) {
            try {
                EntrySignalRow signal = signals.find(en.getKey()).orElseThrow();
                if (signal.tradeDate().isAfter(through)) {
                    continue;
                }
                InstrumentRow row = directory.require(signal.instrumentId());
                LocalDate from = signal.tradeDate().minusDays(th.windowCalendarDays());
                List<DailyBar> raw = bars.find(row.instrument(), row.id(), from, through);
                List<RehabFactor> factors = rehabs.find(row.instrument(), row.id());
                Set<LocalDate> calendar = new HashSet<>(days.between(Market.US, from, through));
                for (SignalTrackRow t : en.getValue()) {
                    SignalTrackRow next = recompute(signal, t, raw, factors, calendar, through);
                    tracks.update(next);
                    updated++;
                    closed += "CLOSED".equals(next.status()) ? 1 : 0;
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn("信号 #{} 账本重算失败：{}", en.getKey(), e.toString());
            }
        }
        return new Summary(updated, closed, failed);
    }

    SignalTrackRow recompute(EntrySignalRow signal, SignalTrackRow t, List<DailyBar> raw, List<RehabFactor> factors,
                             Set<LocalDate> calendar, LocalDate through) {
        PaperTrade.Result r = SignalTrades.simulate(raw, factors, calendar, signal.tradeDate(), signal.close().doubleValue(),
                t.stop().doubleValue(), through, th, PaperTrade.Rules.of(th));
        if (r == null) {
            return new SignalTrackRow(t.signalId(), t.variant(), "PENDING_ENTRY", t.stop(), t.plusOneR(), null, null, false,
                    null, null, null, null, null, null, null, null, through, null);
        }
        boolean open = r.reason() == PaperTrade.ExitReason.OPEN;
        return new SignalTrackRow(t.signalId(), t.variant(), open ? "OPEN" : "CLOSED", t.stop(), t.plusOneR(), r.entryDate(),
                d(r.entry()), r.touchedPlusOneR(), r.exitDate(), r.exit() == null ? null : d(r.exit()),
                open ? null : r.reason().name(), r.r() == null ? null : d(r.r()),
                r.exit() == null ? null : d((r.exit() - r.entry()) / r.entry()), d(r.mfeR()), d(r.maeR()), r.barsHeld(),
                through, null);
    }

    private static BigDecimal d(double v) {
        return BigDecimal.valueOf(v).setScale(6, RoundingMode.HALF_UP);
    }
}
