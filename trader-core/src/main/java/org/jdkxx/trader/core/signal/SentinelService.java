package org.jdkxx.trader.core.signal;

import org.jdkxx.trader.core.marketdata.InstrumentDirectory;
import org.jdkxx.trader.core.marketdata.bars.SettledCutoff;
import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.RehabFactor;
import org.jdkxx.trader.domain.signal.GateResult;
import org.jdkxx.trader.domain.signal.PaperTrade;
import org.jdkxx.trader.domain.signal.SentinelEvaluation;
import org.jdkxx.trader.domain.signal.SentinelThresholds;
import org.jdkxx.trader.domain.signal.SignalInputs;
import org.jdkxx.trader.domain.signal.SignalSuppression;
import org.jdkxx.trader.domain.signal.SignalTrades;
import org.jdkxx.trader.storage.marketdata.DailyBarRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.jdkxx.trader.storage.marketdata.RehabFactorRepository;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 入场哨兵的只读计算：单日判定与区间回放。不落库、不调网关、不调模型（跑批与落库是步骤 3 的事）。
 * 判定日不得晚于收盘落定的交易日（{@link SettledCutoff}），盘中价不参与判定。
 */
public class SentinelService {

    /** 回放区间上限：池与持仓有 20 年日 K。 */
    static final int MAX_REPLAY_YEARS = 21;

    private final InstrumentDirectory directory;
    private final DailyBarRepository bars;
    private final RehabFactorRepository rehabs;
    private final TradingDayRepository days;
    private final SettledCutoff cutoff;
    private final SentinelThresholds thresholds;

    public SentinelService(InstrumentDirectory directory, DailyBarRepository bars, RehabFactorRepository rehabs,
                           TradingDayRepository days, SettledCutoff cutoff) {
        this.directory = directory;
        this.bars = bars;
        this.rehabs = rehabs;
        this.days = days;
        this.cutoff = cutoff;
        this.thresholds = SentinelThresholds.V1;
    }

    public record Judgement(String symbol, SentinelEvaluation evaluation, List<LocalDate> droppedNonTradingDays,
                            List<LocalDate> missingTradingDays, String fingerprint, Map<String, Object> thresholds) {
    }

    /** 单日判定。date 为空取最近收盘落定的交易日。 */
    public Judgement evaluate(String symbol, LocalDate date) {
        LocalDate settled = cutoff.current();
        LocalDate asOf = date == null ? settled : date;
        if (asOf.isAfter(settled)) {
            throw new IllegalStateException("判定日 " + asOf + " 晚于收盘落定的交易日 " + settled);
        }
        InstrumentRow row = directory.require(symbol);
        LocalDate from = asOf.minusDays(thresholds.windowCalendarDays());
        List<DailyBar> raw = bars.find(row.instrument(), row.id(), from, asOf);
        List<RehabFactor> factors = rehabs.find(row.instrument(), row.id());
        List<LocalDate> calendar = days.between(Market.US, from, asOf);
        SignalInputs.Prepared p = SignalInputs.prepare(raw, factors, calendar, asOf, thresholds);
        SentinelEvaluation e = p.skipped() != null ? p.skipped()
                : org.jdkxx.trader.domain.signal.SentinelEvaluator.evaluate(p.bars(), asOf, thresholds);
        return new Judgement(row.symbol(), e, p.droppedNonTradingDays(), p.missingTradingDays(), p.fingerprint(), thresholds.toMap());
    }

    public record ChartBar(LocalDate tradeDate, double open, double high, double low, double close, long volume) {
    }

    /**
     * 画图用的 K 线，价格尺度折回 asOf 那天（与当天的信号价位对齐，之后遇到拆股也连续）。
     * 默认 to = 收盘落定日、asOf = to、from = asOf 往前一年；区间最长 3 年。
     */
    public List<ChartBar> chartBars(String symbol, LocalDate asOf, LocalDate from, LocalDate to) {
        LocalDate settled = cutoff.current();
        LocalDate end = to == null || to.isAfter(settled) ? settled : to;
        LocalDate anchor = asOf == null ? end : asOf;
        LocalDate start = from == null ? anchor.minusYears(1) : from;
        if (start.isAfter(end) || anchor.isAfter(end)) {
            throw new IllegalArgumentException("需要 from ≤ asOf ≤ to");
        }
        if (start.isBefore(end.minusYears(3))) {
            throw new IllegalArgumentException("区间不能超过 3 年");
        }
        InstrumentRow row = directory.require(symbol);
        List<DailyBar> raw = bars.find(row.instrument(), row.id(), start, end);
        Set<LocalDate> calendar = new HashSet<>(days.between(Market.US, start, end));
        return SignalTrades.scaledTo(raw, rehabs.find(row.instrument(), row.id()), calendar, end, anchor).stream()
                .map(b -> new ChartBar(b.date(), round(b.open()), round(b.high()), round(b.low()), round(b.close()), Math.round(b.volume())))
                .toList();
    }

    private static double round(double v) {
        return java.math.BigDecimal.valueOf(v).setScale(4, java.math.RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * 回放的一天。outcome 按边沿与冷却算（历史上没有 AI 结论，按"无结论不阻断"处理），
     * 与实盘跑批的区别只在 AI 否决层。
     */
    /**
     * @param gates 四门结论缩写，按趋势 / 定位 / 触发 / 风控顺序：P 通过、F 不过、U 不可判定；不予判定时为 null
     */
    public record ReplayDay(LocalDate date, SentinelEvaluation.Status status, String gates, int gatesPassed,
                            GateResult.Gate firstBlockingGate, SignalSuppression.Outcome outcome, Double close, Double atr14,
                            Double rvol, Double zoneBottom, Double stop, Double stopDistance, String detail) {
    }

    /**
     * @param trades 每条 SIGNAL 的纸面交易（{@link PaperTrade}，价格为回放末日口径）；没要求时为 null
     * @param exitVariant 纸面交易用的出场变体说明；信号集合始终按 sentinel-v1 判定，变体只改出场，便于同一批信号配对比较
     */
    public record Replay(String symbol, LocalDate from, LocalDate to, Map<String, Long> statusCounts,
                         Map<String, Long> outcomeCounts, List<ReplayDay> days, String exitVariant,
                         List<PaperTrade.Result> trades) {
    }

    public Replay replay(String symbol, LocalDate from, LocalDate to) {
        return replay(symbol, from, to, false, null, false);
    }

    /**
     * @param stopAtrMultiple 纸面交易的止损 ATR 倍数（配对比较用，只改出场不改判定）；null 取 sentinel-v1 的 2.0
     * @param halfAtPlusOneR  纸面交易是否 +1R 减半仓（对照变体）
     */
    public Replay replay(String symbol, LocalDate from, LocalDate to, boolean withTrades, Double stopAtrMultiple,
                         boolean halfAtPlusOneR) {
        LocalDate settled = cutoff.current();
        LocalDate end = to == null || to.isAfter(settled) ? settled : to;
        LocalDate start = from == null ? end.minusYears(1) : from;
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("from 不能晚于 to");
        }
        if (start.isBefore(end.minusYears(MAX_REPLAY_YEARS))) {
            throw new IllegalArgumentException("回放区间不能超过 " + MAX_REPLAY_YEARS + " 年");
        }
        InstrumentRow row = directory.require(symbol);
        LocalDate loadFrom = start.minusDays(thresholds.windowCalendarDays());
        // 纸面交易要走到回放区间之后，K 线取到收盘落定日
        List<DailyBar> raw = bars.find(row.instrument(), row.id(), loadFrom, withTrades ? settled : end);
        List<RehabFactor> factors = rehabs.find(row.instrument(), row.id());
        List<LocalDate> calendarList = days.between(Market.US, loadFrom, end);
        Set<LocalDate> calendar = new HashSet<>(calendarList);

        List<ReplayDay> out = new ArrayList<>();
        Map<String, Long> statusCounts = new LinkedHashMap<>();
        Map<String, Long> outcomeCounts = new LinkedHashMap<>();
        boolean previousPassed = false;
        Integer sinceLastSignal = null;
        List<SentinelEvaluation> signals = new ArrayList<>();
        int lo = 0;
        for (LocalDate asOf : calendarList) {
            if (asOf.isBefore(start)) {
                continue;
            }
            // 只把窗口内的 K 线交给判定，免得每天都扫全部历史
            LocalDate windowStart = asOf.minusDays(thresholds.windowCalendarDays());
            while (lo < raw.size() && raw.get(lo).tradeDate().isBefore(windowStart)) {
                lo++;
            }
            int hi = lo;
            while (hi < raw.size() && !raw.get(hi).tradeDate().isAfter(asOf)) {
                hi++;
            }
            SentinelEvaluation e = SignalInputs.judge(raw.subList(lo, hi), factors, calendar, asOf, thresholds);
            if (sinceLastSignal != null) {
                sinceLastSignal++;
            }
            SignalSuppression.Outcome outcome = SignalSuppression.afterAi(
                    SignalSuppression.beforeAi(e.allPassed(),
                            previousPassed ? SignalSuppression.PreviousDay.PASSED : SignalSuppression.PreviousDay.NOT_PASSED,
                            sinceLastSignal, thresholds),
                    SignalSuppression.AiVerdict.ABSENT);
            if (outcome == SignalSuppression.Outcome.SIGNAL) {
                sinceLastSignal = 0;
                signals.add(e);
            }
            previousPassed = e.allPassed();

            GateResult risk = e.gates().stream().filter(g -> g.gate() == GateResult.Gate.RISK).findFirst().orElse(null);
            String gates = e.gates().isEmpty() ? null : e.gates().stream()
                    .map(g -> g.verdict().name().substring(0, 1)).reduce("", String::concat);
            out.add(new ReplayDay(asOf, e.status(), gates, e.gatesPassed(), e.firstBlockingGate().orElse(null), outcome,
                    (Double) e.indicators().get("close"), (Double) e.indicators().get("atr14"), (Double) e.indicators().get("rvol"),
                    e.hitZone() == null ? null : e.hitZone().bottom(),
                    risk == null ? null : (Double) risk.values().get("stop"),
                    risk == null ? null : (Double) risk.values().get("stopDistance"),
                    e.statusDetail()));
            statusCounts.merge(e.status().name(), 1L, Long::sum);
            outcomeCounts.merge(outcome.name(), 1L, Long::sum);
        }
        if (!withTrades) {
            return new Replay(row.symbol(), start, end, statusCounts, outcomeCounts, out, null, null);
        }
        double multiple = stopAtrMultiple == null ? thresholds.stopAtrMultiple() : stopAtrMultiple;
        if (!(multiple > 0 && multiple <= 10)) {
            throw new IllegalArgumentException("stopAtr 取值 (0, 10]");
        }
        PaperTrade.Rules rules = PaperTrade.Rules.of(thresholds);
        if (halfAtPlusOneR) {
            rules = rules.withHalfAtPlusOneR();
        }
        String variant = "止损 min(收盘 − " + multiple + "×ATR, 区底 − " + thresholds.zoneStopAtrMultiple() + "×ATR)"
                + (halfAtPlusOneR ? "，+1R 减半仓" : "，不减半仓");
        return new Replay(row.symbol(), start, end, statusCounts, outcomeCounts, out, variant,
                trades(raw, factors, settled, signals, multiple, rules));
    }

    /** 逐条模拟（与纸面账本同一入口 {@link SignalTrades}）：价格为判定日口径。 */
    private List<PaperTrade.Result> trades(List<DailyBar> raw, List<RehabFactor> factors, LocalDate settled,
                                           List<SentinelEvaluation> signals, double stopAtrMultiple, PaperTrade.Rules rules) {
        LocalDate first = raw.isEmpty() ? settled : raw.get(0).tradeDate();
        Set<LocalDate> tradingDays = new HashSet<>(days.between(Market.US, first, settled));
        List<PaperTrade.Result> out = new ArrayList<>();
        for (SentinelEvaluation e : signals) {
            double close = (Double) e.indicators().get("close");
            double atr = (Double) e.indicators().get("atr14");
            double stop = SignalTrades.stop(close, atr, e.hitZone() == null ? null : e.hitZone().bottom(), stopAtrMultiple, thresholds);
            PaperTrade.Result r = SignalTrades.simulate(raw, factors, tradingDays, e.asOf(), close, stop, settled, thresholds, rules);
            if (r != null) {
                out.add(r);
            }
        }
        return out;
    }
}
