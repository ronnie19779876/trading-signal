import org.jdkxx.trader.domain.signal.PaperTrade;
import org.jdkxx.trader.domain.signal.SignalBar;

import java.time.LocalDate;
import java.util.List;

/**
 * H2 的备选出场（ARCHITECTURE §24.6）：在 {@link PaperTrade#simulate} 的逐日循环里加一道「盘中初始止损」，其余逐行照抄。
 *
 * <ul>
 *   <li>开盘 ≤ 初始止损 → 按开盘成交（跳空）；否则最低 ≤ 初始止损 → 按止损价成交；</li>
 *   <li>吊灯止损、时间止损、+1R 判定都不变（吊灯止损仍按收盘判定，用户认可不改）；</li>
 *   <li>{@code intraday=false} 时必须与 {@link PaperTrade#simulate} 逐笔相同——这是研究实现的第一道关。</li>
 * </ul>
 */
final class IntradayStopTrade {

    enum Exit { STOP, CHANDELIER, TIME, OPEN, INTRADAY_GAP, INTRADAY_TOUCH }

    /**
     * @param whipsaw 盘中止损出场的那天收盘仍在初始止损之上（被洗出去）
     */
    record Result(LocalDate exitDate, Double exit, Exit reason, Double r, int barsHeld, double lastClose, boolean whipsaw) {
    }

    private IntradayStopTrade() {
    }

    static Result simulate(List<SignalBar> bars, double[] atr, int signal, double stop, PaperTrade.Rules rules, boolean intraday) {
        double close = bars.get(signal).close();
        double risk = close - stop;
        double target = close + risk;
        int entryIndex = signal + 1;
        double entry = bars.get(entryIndex).open();
        boolean touched = false;
        for (int i = entryIndex; i < bars.size(); i++) {
            SignalBar b = bars.get(i);
            if (intraday) {
                Double fill = b.open() <= stop ? Double.valueOf(b.open()) : b.low() <= stop ? Double.valueOf(stop) : null;
                if (fill != null) {
                    Exit why = b.open() <= stop ? Exit.INTRADAY_GAP : Exit.INTRADAY_TOUCH;
                    return new Result(b.date(), fill, why, (fill - entry) / risk, i - entryIndex + 1, fill, b.close() > stop);
                }
            }
            if (!touched && b.high() >= target) {
                touched = true;
            }
            double activeStop = stop;
            Exit stopReason = Exit.STOP;
            if (touched && !Double.isNaN(atr[i])) {
                double hh = Double.NEGATIVE_INFINITY;
                for (int k = Math.max(0, i - rules.chandelierPeriod() + 1); k <= i; k++) {
                    hh = Math.max(hh, bars.get(k).high());
                }
                double chandelier = hh - rules.chandelierAtrMultiple() * atr[i];
                if (chandelier > activeStop) {
                    activeStop = chandelier;
                    stopReason = Exit.CHANDELIER;
                }
            }
            Exit reason = null;
            if (b.close() <= activeStop) {
                reason = stopReason;
            } else if (!touched && i - entryIndex >= rules.timeStopDays()) {
                reason = Exit.TIME;
            }
            if (reason != null) {
                return new Result(b.date(), b.close(), reason, (b.close() - entry) / risk, i - entryIndex + 1, b.close(), false);
            }
        }
        SignalBar last = bars.get(bars.size() - 1);
        return new Result(null, null, Exit.OPEN, null, bars.size() - entryIndex, last.close(), false);
    }
}
