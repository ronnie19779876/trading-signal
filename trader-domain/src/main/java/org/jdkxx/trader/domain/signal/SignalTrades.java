package org.jdkxx.trader.domain.signal;

import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.RehabFactor;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * 一条信号的纸面交易（纯函数）：回放统计与纸面跟踪账本共用同一入口，保证两边算出来一样。
 *
 * <ul>
 *   <li>取数固定为 判定日往前 600 自然日 至 {@code through}：吊灯止损用的 Wilder ATR 与起点有关，起点只随信号走，
 *       不随同一标的其它信号或查询区间变化；</li>
 *   <li>整段按 {@code through} 口径做结构换算（持有期间遇到拆股也连续），模拟完再把价格换回<b>判定日口径</b>，
 *       与 entry_signal 里的价位可直接对照；</li>
 *   <li>剔除交易日历之外的 K 线（与判定一致）。</li>
 * </ul>
 */
public final class SignalTrades {

    private SignalTrades() {
    }

    /**
     * @param evalClose  判定日收盘（判定日口径 = 原始价）
     * @param stopAtEval 止损（判定日口径）
     * @return 判定日之后还没有 K 线时返回 null；换算失败抛 IllegalStateException
     */
    public static PaperTrade.Result simulate(List<DailyBar> raw, List<RehabFactor> factors, Set<LocalDate> tradingDays,
                                             LocalDate signalDate, double evalClose, double stopAtEval, LocalDate through,
                                             SentinelThresholds th, PaperTrade.Rules rules) {
        LocalDate from = signalDate.minusDays(th.windowCalendarDays());
        List<DailyBar> slice = raw.stream()
                .filter(b -> !b.tradeDate().isBefore(from) && !b.tradeDate().isAfter(through) && tradingDays.contains(b.tradeDate()))
                .toList();
        StructuralAdjustment.Result adjusted = StructuralAdjustment.apply(slice, factors, through);
        if (!adjusted.ok()) {
            throw new IllegalStateException("纸面交易的价量口径换算失败：" + adjusted.problem());
        }
        List<SignalBar> series = adjusted.bars();
        int index = -1;
        for (int i = 0; i < series.size(); i++) {
            if (series.get(i).date().equals(signalDate)) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            throw new IllegalStateException(signalDate + " 没有 K 线，无法模拟");
        }
        double[] high = series.stream().mapToDouble(SignalBar::high).toArray();
        double[] low = series.stream().mapToDouble(SignalBar::low).toArray();
        double[] close = series.stream().mapToDouble(SignalBar::close).toArray();
        double[] atr = Indicators.wilderAtr(high, low, close, th.atrPeriod());
        double scale = close[index] / evalClose;
        PaperTrade.Result r = PaperTrade.simulate(series, atr, index, stopAtEval * scale, rules);
        if (r == null) {
            return null;
        }
        return new PaperTrade.Result(r.signalDate(), r.entryDate(), r.entry() / scale, r.stop() / scale, r.plusOneR() / scale,
                r.touchedPlusOneR(), r.exitDate(), r.exit() == null ? null : r.exit() / scale, r.reason(), r.r(),
                r.mfeR(), r.maeR(), r.barsHeld());
    }

    /**
     * 画图用的 K 线：整段按 {@code through} 做结构换算（跨拆股连续），再整体折回 {@code asOf} 那天的价格尺度，
     * 使判定日的价位（止损、+1R、支撑区）与图上的 K 线对齐。asOf 当天没有 K 线时取之前最近的一根定尺度；
     * 窗口内一根都没有返回空列表。成交量按同样的股数比例折算。
     */
    public static List<SignalBar> scaledTo(List<DailyBar> raw, List<RehabFactor> factors, Set<LocalDate> tradingDays,
                                           LocalDate through, LocalDate asOf) {
        List<DailyBar> slice = raw.stream()
                .filter(b -> !b.tradeDate().isAfter(through) && tradingDays.contains(b.tradeDate()))
                .toList();
        StructuralAdjustment.Result adjusted = StructuralAdjustment.apply(slice, factors, through);
        if (!adjusted.ok()) {
            throw new IllegalStateException("价量口径换算失败：" + adjusted.problem());
        }
        DailyBar anchorRaw = null;
        SignalBar anchorAdj = null;
        for (int i = 0; i < slice.size(); i++) {
            if (!slice.get(i).tradeDate().isAfter(asOf) && !slice.get(i).blank()) {
                anchorRaw = slice.get(i);
            }
        }
        if (anchorRaw == null) {
            return List.of();
        }
        for (SignalBar b : adjusted.bars()) {
            if (b.date().equals(anchorRaw.tradeDate())) {
                anchorAdj = b;
            }
        }
        double price = anchorRaw.close().doubleValue() / anchorAdj.close();
        double volume = anchorAdj.volume() == 0 ? 1 : anchorRaw.volume() / anchorAdj.volume();
        return adjusted.bars().stream()
                .map(b -> new SignalBar(b.date(), b.open() * price, b.high() * price, b.low() * price, b.close() * price,
                        b.volume() * volume))
                .toList();
    }

    /** 某个止损 ATR 倍数下的止损（判定日口径）：min(收盘 − m×ATR, 区底 − 0.5×ATR)，没有命中区只用 ATR 腿。 */
    public static double stop(double close, double atr, Double zoneBottom, double atrMultiple, SentinelThresholds th) {
        double stop = close - atrMultiple * atr;
        return zoneBottom == null ? stop : Math.min(stop, zoneBottom - th.zoneStopAtrMultiple() * atr);
    }
}
