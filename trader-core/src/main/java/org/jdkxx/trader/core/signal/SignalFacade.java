package org.jdkxx.trader.core.signal;

import org.jdkxx.trader.core.marketdata.InstrumentDirectory;
import org.jdkxx.trader.core.marketdata.Jobs;
import org.jdkxx.trader.core.marketdata.bars.SettledCutoff;
import org.jdkxx.trader.core.marketdata.jobs.JobService;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.signal.SentinelThresholds;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;
import org.jdkxx.trader.storage.signal.AiAnalysisRepository;
import org.jdkxx.trader.storage.signal.EntrySignalRepository;
import org.jdkxx.trader.storage.signal.EntrySignalRow;
import org.jdkxx.trader.storage.signal.SignalEvaluationRepository;
import org.jdkxx.trader.storage.signal.SignalEvaluationRow;
import org.jdkxx.trader.storage.signal.SignalTrackRepository;
import org.jdkxx.trader.storage.signal.SignalTrackRow;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;

/** 入场信号的入口：提交评估作业、查信号 / 评估 / 账本、改信号状态。 */
public class SignalFacade {

    private final JobService jobs;
    private final SignalEvaluationService evaluation;
    private final SentinelService sentinel;
    private final SignalEvaluationRepository evaluations;
    private final EntrySignalRepository signals;
    private final SignalTrackRepository tracks;
    private final InstrumentDirectory directory;
    private final TradingDayRepository days;
    private final SettledCutoff cutoff;
    private final AiAnalysisRepository analyses;
    private final String version = SentinelThresholds.V1.version();

    public SignalFacade(JobService jobs, SignalEvaluationService evaluation, SentinelService sentinel,
                        SignalEvaluationRepository evaluations, EntrySignalRepository signals, SignalTrackRepository tracks,
                        InstrumentDirectory directory, TradingDayRepository days, SettledCutoff cutoff,
                        AiAnalysisRepository analyses) {
        this.jobs = jobs;
        this.evaluation = evaluation;
        this.sentinel = sentinel;
        this.evaluations = evaluations;
        this.signals = signals;
        this.tracks = tracks;
        this.directory = directory;
        this.days = days;
        this.cutoff = cutoff;
        this.analyses = analyses;
    }

    /** 提交评估作业。date 缺省取收盘落定日；晚于它 409、非交易日 400，都在提交前拦下。 */
    public long evaluate(String trigger, LocalDate date) {
        LocalDate settled = cutoff.current();
        LocalDate asOf = date == null ? settled : date;
        if (asOf.isAfter(settled)) {
            throw new IllegalStateException("判定日 " + asOf + " 晚于收盘落定的交易日 " + settled);
        }
        if (!days.isTradingDay(Market.US, asOf)) {
            throw new IllegalArgumentException(asOf + " 不是交易日");
        }
        return jobs.submit(Jobs.SIGNAL_EVALUATION, trigger, ctx -> evaluation.run(ctx, asOf));
    }

    public record SignalView(EntrySignalRow signal, SignalTrackRow base) {
    }

    public List<SignalView> list(LocalDate from, LocalDate to, Set<String> statuses, boolean poolOnly, String origin) {
        LocalDate end = to == null ? cutoff.current() : to;
        LocalDate start = from == null ? end.minusDays(30) : from;
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("from 不能晚于 to");
        }
        List<EntrySignalRow> rows = signals.list(start, end, statuses, poolOnly, origin);
        Map<Long, SignalTrackRow> base = new HashMap<>();
        tracks.bySignals(rows.stream().map(EntrySignalRow::id).toList()).stream()
                .filter(t -> "BASE".equals(t.variant()))
                .forEach(t -> base.put(t.signalId(), t));
        return rows.stream().map(s -> new SignalView(s, base.get(s.id()))).toList();
    }

    /**
     * @param fingerprintMatches 按当前库里数据重算的指纹与存档是否一致；不一致说明 K 线或因子被重拉改过
     * @param recomputed         存档没有判定明细时现场重算的结果（有明细时为 null）
     */
    public record SignalDetail(EntrySignalRow signal, SignalEvaluationRow evaluation, boolean fingerprintMatches,
                               SentinelService.Judgement recomputed, List<SignalTrackRow> tracks) {
    }

    public SignalDetail detail(long id) {
        EntrySignalRow s = signals.find(id).orElseThrow(() -> new NoSuchElementException("信号 #" + id + " 不存在"));
        SignalEvaluationRow e = evaluations.find(s.instrumentId(), s.tradeDate(), s.rulesetVersion()).orElse(null);
        SentinelService.Judgement now = sentinel.evaluate(s.symbol(), s.tradeDate());
        boolean matches = e != null && now.fingerprint().equals(e.inputFingerprint());
        return new SignalDetail(s, e, matches, e != null && e.detail() != null ? null : now, tracks.bySignal(id));
    }

    /** NEW → ACKNOWLEDGED / DISMISSED；ACKNOWLEDGED → DISMISSED。其余 409。 */
    public EntrySignalRow changeStatus(long id, String to, String note) {
        EntrySignalRow s = signals.find(id).orElseThrow(() -> new NoSuchElementException("信号 #" + id + " 不存在"));
        List<String> from = switch (to) {
            case "ACKNOWLEDGED" -> List.of("NEW");
            case "DISMISSED" -> List.of("NEW", "ACKNOWLEDGED");
            default -> throw new IllegalArgumentException("status 只能是 ACKNOWLEDGED 或 DISMISSED");
        };
        if (!signals.transition(id, from, to, note)) {
            throw new IllegalStateException("信号 #" + id + " 当前是 " + s.status() + "，不能改为 " + to);
        }
        return signals.find(id).orElseThrow();
    }

    /** 某天的全部评估；outcome / gate 可过滤，poolOnly 只看池与持仓。 */
    public List<SignalEvaluationRow> evaluationsOn(LocalDate date, String outcome, String firstBlockingGate, boolean poolOnly) {
        LocalDate d = date == null ? cutoff.current() : date;
        return evaluations.on(d, version).stream()
                .filter(r -> outcome == null || outcome.equals(r.outcome()) || outcome.equals(r.status()))
                .filter(r -> firstBlockingGate == null || firstBlockingGate.equals(r.firstBlockingGate()))
                .filter(r -> !poolOnly || !"UNIVERSE".equals(r.role()))
                .toList();
    }

    public InstrumentRow instrument(String symbol) {
        return directory.require(symbol);
    }

    public List<SignalEvaluationRow> history(String symbol, LocalDate from, LocalDate to) {
        InstrumentRow row = directory.require(symbol);
        LocalDate end = to == null ? cutoff.current() : to;
        LocalDate start = from == null ? end.minusDays(90) : from;
        return evaluations.history(row.id(), start, end, version);
    }

    /** 账本汇总：每个变体按来源分开统计；BASE 与 STOP_2_5 在同一批已平仓信号上的收益率配对差。 */
    /**
     * @param ai 模型裁决分组：VETO 被否决（信号状态 VETOED）、ALLOW 调过模型且放行、NONE 没有模型结论（补跑、未开启、失败、预算跳过）
     */
    public record LedgerStats(String variant, String origin, String ai, int total, int open, int pending, int closed, Double winRate,
                              Double meanR, Double meanReturn) {
    }

    public record Ledger(List<LedgerStats> stats, Integer pairedCount, Double pairedMeanReturnDiff, List<LedgerEntry> entries) {
    }

    public record LedgerEntry(EntrySignalRow signal, SignalTrackRow track) {
    }

    public Ledger ledger(String variant, String status) {
        List<SignalTrackRow> all = tracks.list(null, null);
        Map<Long, EntrySignalRow> signalById = new HashMap<>();
        signals.findAll(all.stream().map(SignalTrackRow::signalId).distinct().toList())
                .forEach(s -> signalById.put(s.id(), s));
        Map<Long, String> verdicts = analyses.verdicts(signalById.values().stream().map(EntrySignalRow::aiAnalysisId)
                .filter(java.util.Objects::nonNull).distinct().toList());
        List<LedgerStats> stats = new ArrayList<>();
        Map<List<String>, List<SignalTrackRow>> grouped = all.stream().collect(Collectors.groupingBy(t -> {
            EntrySignalRow sig = signalById.get(t.signalId());
            return List.of(t.variant(), sig.origin(), aiGroup(sig, verdicts));
        }, () -> new java.util.TreeMap<>(java.util.Comparator.comparing((List<String> k) -> String.join("|", k))), Collectors.toList()));
        grouped.forEach((key, rows) -> {
            List<SignalTrackRow> closed = rows.stream().filter(t -> "CLOSED".equals(t.status())).toList();
            stats.add(new LedgerStats(key.get(0), key.get(1), key.get(2), rows.size(), count(rows, "OPEN"), count(rows, "PENDING_ENTRY"),
                    closed.size(),
                    closed.isEmpty() ? null : (double) closed.stream().filter(t -> t.rMultiple().signum() > 0).count() / closed.size(),
                    mean(closed.stream().map(SignalTrackRow::rMultiple).toList()),
                    mean(closed.stream().map(SignalTrackRow::returnPct).toList())));
        });
        Map<Long, BigDecimal> base = closedReturns(all, "BASE");
        Map<Long, BigDecimal> wide = closedReturns(all, "STOP_2_5");
        List<BigDecimal> diffs = base.entrySet().stream().filter(en -> wide.containsKey(en.getKey()))
                .map(en -> wide.get(en.getKey()).subtract(en.getValue())).toList();
        List<LedgerEntry> entries = all.stream()
                .filter(t -> variant == null || variant.equals(t.variant()))
                .filter(t -> status == null || status.equals(t.status()))
                .map(t -> new LedgerEntry(signalById.get(t.signalId()), t))
                .toList();
        return new Ledger(stats, diffs.size(), mean(diffs), entries);
    }

    /**
     * 按挂在信号上的那次分析的裁决分组。预算跳过、失败、拒答、截断也会写一行分析并挂到信号上（裁决 ABSENT），
     * 3.0.0 只看 aiAnalysisId 是否为空，把它们全算成了 ALLOW——四巫日（2026-09-18）31 条无结论被算成放行。
     */
    static String aiGroup(EntrySignalRow s, Map<Long, String> verdicts) {
        if ("VETOED".equals(s.status())) {
            return "VETO";
        }
        return s.aiAnalysisId() != null && "ALLOW".equals(verdicts.get(s.aiAnalysisId())) ? "ALLOW" : "NONE";
    }

    private static int count(List<SignalTrackRow> rows, String status) {
        return (int) rows.stream().filter(t -> status.equals(t.status())).count();
    }

    private static Map<Long, BigDecimal> closedReturns(List<SignalTrackRow> rows, String variant) {
        return rows.stream().filter(t -> variant.equals(t.variant()) && "CLOSED".equals(t.status()))
                .collect(Collectors.toMap(SignalTrackRow::signalId, SignalTrackRow::returnPct));
    }

    private static Double mean(List<BigDecimal> values) {
        return values.isEmpty() ? null : values.stream().mapToDouble(BigDecimal::doubleValue).average().orElseThrow();
    }
}
