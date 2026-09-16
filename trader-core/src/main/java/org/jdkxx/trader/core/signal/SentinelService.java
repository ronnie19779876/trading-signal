package org.jdkxx.trader.core.signal;

import org.jdkxx.trader.core.marketdata.InstrumentDirectory;
import org.jdkxx.trader.core.marketdata.bars.SettledCutoff;
import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.RehabFactor;
import org.jdkxx.trader.domain.signal.GateResult;
import org.jdkxx.trader.domain.signal.SentinelEvaluation;
import org.jdkxx.trader.domain.signal.SentinelThresholds;
import org.jdkxx.trader.domain.signal.SignalInputs;
import org.jdkxx.trader.domain.signal.SignalSuppression;
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
                            List<LocalDate> missingTradingDays, Map<String, Object> thresholds) {
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
        return new Judgement(row.symbol(), e, p.droppedNonTradingDays(), p.missingTradingDays(), thresholds.toMap());
    }

    /**
     * 回放的一天。outcome 按边沿与冷却算（历史上没有 AI 结论，按"无结论不阻断"处理），
     * 与实盘跑批的区别只在 AI 否决层。
     */
    public record ReplayDay(LocalDate date, SentinelEvaluation.Status status, int gatesPassed, GateResult.Gate firstBlockingGate,
                            SignalSuppression.Outcome outcome, Double close, Double stop, Double stopDistance, String detail) {
    }

    public record Replay(String symbol, LocalDate from, LocalDate to, Map<String, Long> statusCounts,
                         Map<String, Long> outcomeCounts, List<ReplayDay> days) {
    }

    public Replay replay(String symbol, LocalDate from, LocalDate to) {
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
        List<DailyBar> raw = bars.find(row.instrument(), row.id(), loadFrom, end);
        List<RehabFactor> factors = rehabs.find(row.instrument(), row.id());
        List<LocalDate> calendarList = days.between(Market.US, loadFrom, end);
        Set<LocalDate> calendar = new HashSet<>(calendarList);

        List<ReplayDay> out = new ArrayList<>();
        Map<String, Long> statusCounts = new LinkedHashMap<>();
        Map<String, Long> outcomeCounts = new LinkedHashMap<>();
        boolean previousPassed = false;
        Integer sinceLastSignal = null;
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
            }
            previousPassed = e.allPassed();

            GateResult risk = e.gates().stream().filter(g -> g.gate() == GateResult.Gate.RISK).findFirst().orElse(null);
            out.add(new ReplayDay(asOf, e.status(), e.gatesPassed(), e.firstBlockingGate().orElse(null), outcome,
                    (Double) e.indicators().get("close"),
                    risk == null ? null : (Double) risk.values().get("stop"),
                    risk == null ? null : (Double) risk.values().get("stopDistance"),
                    e.statusDetail()));
            statusCounts.merge(e.status().name(), 1L, Long::sum);
            outcomeCounts.merge(outcome.name(), 1L, Long::sum);
        }
        return new Replay(row.symbol(), start, end, statusCounts, outcomeCounts, out);
    }
}
