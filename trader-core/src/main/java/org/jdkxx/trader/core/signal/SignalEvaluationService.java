package org.jdkxx.trader.core.signal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdkxx.trader.core.marketdata.bars.SettledCutoff;
import org.jdkxx.trader.core.marketdata.jobs.JobContext;
import org.jdkxx.trader.core.marketdata.universe.UniverseScope;
import org.jdkxx.trader.core.signal.ai.AiVetoService;
import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.PoolRole;
import org.jdkxx.trader.domain.RehabFactor;
import org.jdkxx.trader.domain.signal.ExitPlan;
import org.jdkxx.trader.domain.signal.GateResult;
import org.jdkxx.trader.domain.signal.SentinelEvaluation;
import org.jdkxx.trader.domain.signal.SentinelEvaluator;
import org.jdkxx.trader.domain.signal.SentinelThresholds;
import org.jdkxx.trader.domain.signal.SignalInputs;
import org.jdkxx.trader.domain.signal.SignalSuppression;
import org.jdkxx.trader.domain.signal.SignalSuppression.Outcome;
import org.jdkxx.trader.domain.signal.SignalSuppression.PreviousDay;
import org.jdkxx.trader.domain.signal.SignalTrades;
import org.jdkxx.trader.storage.marketdata.DailyBarRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.jdkxx.trader.storage.marketdata.RehabFactorRepository;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;
import org.jdkxx.trader.storage.signal.EntrySignalRepository;
import org.jdkxx.trader.storage.signal.EntrySignalRow;
import org.jdkxx.trader.storage.signal.SignalEvaluationRepository;
import org.jdkxx.trader.storage.signal.SignalEvaluationRow;
import org.jdkxx.trader.storage.signal.SignalStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 入场哨兵每日评估作业体（{@code SIGNAL_EVALUATION}）：全量成分股 ∪ 池与持仓，去掉基准。
 *
 * <p>每只：数据层检查 → 四门判定 → 边沿（上一交易日的评估行；没有或当天不予判定时按判定规则现场重算，AI 信息未知按未否决）
 * → 冷却（距上一条未被否决的信号的交易日数）→ AI（步骤 3 一律按没有结论）→ 评估行、信号、两个出场变体的账本行同一事务写入。
 * 收尾：过期信号、重算未平仓的账本。
 *
 * <p>重跑同一天：评估覆盖，已发出的信号不动（唯一键挡住）；重算后不再是信号的由审计提示。
 * 对过去日期补跑时信号记 BACKFILL，统计与实盘分开。
 */
public class SignalEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(SignalEvaluationService.class);

    /** 账本变体 → 止损 ATR 倍数。BASE 即 sentinel-v1 的出场。 */
    static final Map<String, Double> VARIANTS = Map.of("BASE", 2.0, "STOP_2_5", 2.5);

    static final int BATCH = 100;

    private final UniverseScope scope;
    private final DailyBarRepository bars;
    private final RehabFactorRepository rehabs;
    private final TradingDayRepository days;
    private final SignalEvaluationRepository evaluations;
    private final EntrySignalRepository signals;
    private final SignalStore store;
    private final SignalLedgerService ledger;
    private final SettledCutoff cutoff;
    private final ObjectMapper json;
    private final AiVetoService ai;
    private final SentinelThresholds th = SentinelThresholds.V1;

    public SignalEvaluationService(UniverseScope scope, DailyBarRepository bars, RehabFactorRepository rehabs,
                                   TradingDayRepository days, SignalEvaluationRepository evaluations, EntrySignalRepository signals,
                                   SignalStore store, SignalLedgerService ledger, SettledCutoff cutoff, ObjectMapper json,
                                   AiVetoService ai) {
        this.scope = scope;
        this.bars = bars;
        this.rehabs = rehabs;
        this.days = days;
        this.evaluations = evaluations;
        this.signals = signals;
        this.store = store;
        this.ledger = ledger;
        this.cutoff = cutoff;
        this.json = json;
        this.ai = ai;
    }

    /**
     * 评估目标：全量成分股 ∪ 池与持仓，去掉基准。
     *
     * <p>顺序就是 AI 额度的分配顺序：作业里边评估边调模型，每日上限与作业时长先到先得。
     * 所以持仓在前、池其次、池外最后，同一角色内按代码。3.0.0 时成分股按代码在前、池与持仓多半已在其中
     * 而保留原位，额度按字母发放——四巫日（2026-09-18）51 条候选，额度在 G 开头用完，持仓 IBKR 被预算跳过。
     */
    Map<Long, InstrumentRow> targets() {
        Map<Long, InstrumentRow> byId = new HashMap<>();
        scope.universe().forEach(r -> byId.put(r.id(), r));
        scope.candidates().forEach(r -> byId.put(r.id(), r));
        scope.benchmarks().forEach(r -> byId.remove(r.id()));
        Map<Long, PoolRole> roles = scope.roles();
        Map<Long, InstrumentRow> ordered = new LinkedHashMap<>();
        byId.values().stream()
                .sorted(Comparator.comparingInt((InstrumentRow r) -> aiPriority(roles.get(r.id()))).thenComparing(InstrumentRow::symbol))
                .forEach(r -> ordered.put(r.id(), r));
        return ordered;
    }

    /** 持仓 0、池 1、池外 2。 */
    static int aiPriority(PoolRole role) {
        if (role == PoolRole.HOLDING) {
            return 0;
        }
        return role == PoolRole.POOL ? 1 : 2;
    }

    public String run(JobContext ctx, LocalDate asOf) {
        LocalDate settled = cutoff.current();
        if (asOf.isAfter(settled)) {
            throw new IllegalStateException("判定日 " + asOf + " 晚于收盘落定的交易日 " + settled);
        }
        String origin = asOf.equals(settled) ? "LIVE" : "BACKFILL";
        List<LocalDate> calendar = days.between(Market.US, asOf.minusDays(th.windowCalendarDays() + 10), asOf.plusDays(14));
        int at = calendar.indexOf(asOf);
        if (at < 1) {
            throw new IllegalArgumentException(asOf + " 不是交易日（或交易日历不覆盖）");
        }
        LocalDate previousDay = calendar.get(at - 1);
        // 有效期截止日 = 判定日后第 validityDays 个交易日；日历还没到那天时按工作日顺延估算
        LocalDate expiresOn = at + th.validityDays() < calendar.size() ? calendar.get(at + th.validityDays())
                : weekdaysAfter(asOf, th.validityDays());
        Set<LocalDate> calendarSet = new HashSet<>(calendar);

        Map<Long, InstrumentRow> targets = targets();
        boolean aiOn = ai != null && ai.enabledFor(origin);
        AiContext aiContext = new AiContext(aiOn, aiOn ? ai.jobDeadline() : null);
        Map<Long, PoolRole> roles = scope.roles();
        Map<Long, SignalEvaluationRow> previous = evaluations.byInstrumentOn(previousDay, th.version());
        Map<Long, LocalDate> lastSignals = signals.lastSignalDatesBefore(asOf, th.version());

        Map<SentinelEvaluation.Status, Integer> statuses = new EnumMap<>(SentinelEvaluation.Status.class);
        Map<Outcome, Integer> outcomes = new EnumMap<>(Outcome.class);
        int created = 0;
        int poolSignals = 0;
        int failed = 0;
        int done = 0;
        List<InstrumentRow> all = List.copyOf(targets.values());
        // 分批取数、分批写评估行：开发实例经隧道连库时逐只往返要四分钟以上
        for (int start = 0; start < all.size() && !ctx.cancelled(); start += BATCH) {
            List<InstrumentRow> batch = all.subList(start, Math.min(all.size(), start + BATCH));
            Map<Long, org.jdkxx.trader.domain.Instrument> instruments = new LinkedHashMap<>();
            batch.forEach(r -> instruments.put(r.id(), r.instrument()));
            Map<Long, List<DailyBar>> barsById = bars.findMany(instruments, previousDay.minusDays(th.windowCalendarDays()), asOf);
            Map<Long, List<RehabFactor>> factorsById = rehabs.findMany(instruments);
            List<SignalEvaluationRow> plain = new ArrayList<>();
            for (InstrumentRow row : batch) {
                try {
                    String role = roleOf(roles.get(row.id()));
                    Result r = evaluate(row, barsById.getOrDefault(row.id(), List.of()), factorsById.getOrDefault(row.id(), List.of()),
                            asOf, previousDay, calendarSet, calendar, previous.get(row.id()), lastSignals.get(row.id()), role,
                            origin, expiresOn, ctx.id(), plain, aiContext);
                    statuses.merge(r.evaluation().status(), 1, Integer::sum);
                    if (r.outcome() != null) {
                        outcomes.merge(r.outcome(), 1, Integer::sum);
                    }
                    if (r.createdSignal() && r.outcome() == Outcome.SIGNAL) {
                        created++;
                        poolSignals += "UNIVERSE".equals(role) ? 0 : 1;
                    }
                } catch (RuntimeException e) {
                    failed++;
                    log.warn("{} {} 评估失败：{}", row.symbol(), asOf, e.toString());
                }
            }
            evaluations.upsertAll(plain);
            ctx.progress("评估 " + asOf + "：" + Math.min(all.size(), start + BATCH) + " / " + all.size());
        }
        if (ctx.cancelled()) {
            ctx.partial("作业被取消");
        }
        if (failed > 0) {
            ctx.partial(failed + " 只评估失败（见日志）");
        }
        int expired = signals.expire(asOf);
        ctx.progress("重算纸面账本");
        SignalLedgerService.Summary l = ledger.update(asOf);
        if (l.failed() > 0) {
            ctx.partial("账本 " + l.failed() + " 条信号重算失败（见日志）");
        }
        return "评估 " + asOf + (origin.equals("BACKFILL") ? "（补跑）" : "") + "：目标 " + targets.size() + " 只，"
                + describe(statuses) + "；结果 " + describe(outcomes) + "；新信号 " + created + " 条（池与持仓 " + poolSignals
                + "）；过期 " + expired + " 条；账本重算 " + l.updated() + " 条、本次平仓 " + l.closed() + " 条";
    }

    record Result(SentinelEvaluation evaluation, Outcome outcome, boolean createdSignal) {
    }

    /**
     * @param raw   覆盖上一交易日窗口的 K 线：边沿可能要现场重算上一交易日
     * @param plain 不是信号的评估行收集到这里，由调用方批量写入；是信号的与信号、账本行同一事务立即写入
     */
    Result evaluate(InstrumentRow row, List<DailyBar> raw, List<RehabFactor> factors, LocalDate asOf, LocalDate previousDay,
                    Set<LocalDate> calendarSet, List<LocalDate> calendar, SignalEvaluationRow previousRow, LocalDate lastSignal,
                    String role, String origin, LocalDate expiresOn, Long jobRunId, List<SignalEvaluationRow> plain,
                    AiContext aiContext) {
        SignalInputs.Prepared p = SignalInputs.prepare(raw, factors, calendarSet, asOf, th);
        SentinelEvaluation e = p.skipped() != null ? p.skipped() : SentinelEvaluator.evaluate(p.bars(), asOf, th);

        Outcome outcome = null;
        AiVetoService.Outcome aiOutcome = null;
        if (e.status() == SentinelEvaluation.Status.EVALUATED) {
            PreviousDay prev = previousDay(previousRow, raw, factors, calendarSet, previousDay);
            Integer since = tradingDaysSince(calendar, lastSignal, asOf);
            Outcome beforeAi = SignalSuppression.beforeAi(e.allPassed(), prev, since, th);
            // 只对过了边沿与冷却、当天将成为信号的候选调模型
            if (beforeAi == Outcome.PENDING_AI && aiContext.enabled()) {
                aiOutcome = ai.analyze(row, e, "SIGNAL_VETO", jobRunId, aiContext.deadline());
            }
            outcome = SignalSuppression.afterAi(beforeAi,
                    aiOutcome == null ? SignalSuppression.AiVerdict.ABSENT : aiOutcome.verdict());
        }
        boolean keepDetail = !"UNIVERSE".equals(role) || e.gatesPassed() >= 3 || (outcome != null && outcome != Outcome.NO_SIGNAL);
        Map<String, Object> detail = null;
        if (keepDetail) {
            detail = new LinkedHashMap<>();
            detail.put("evaluation", e);
            detail.put("droppedNonTradingDays", p.droppedNonTradingDays());
            detail.put("missingTradingDays", p.missingTradingDays());
        }
        GateResult risk = e.gates().stream().filter(g -> g.gate() == GateResult.Gate.RISK).findFirst().orElse(null);
        SignalEvaluationRow evaluationRow = new SignalEvaluationRow(row.id(), row.symbol(), asOf, th.version(), e.status().name(),
                e.statusDetail(), outcome == null ? null : outcome.name(), gates(e), e.gatesPassed(),
                e.firstBlockingGate().map(Enum::name).orElse(null), role,
                num(e.indicators().get("close")), num(e.indicators().get("atr14")), num(e.indicators().get("rvol")),
                e.hitZone() == null ? null : BigDecimal.valueOf(e.hitZone().bottom()),
                risk == null ? null : num(risk.values().get("stop")), risk == null ? null : num(risk.values().get("stopDistance")),
                p.fingerprint(), detail == null ? null : write(detail), jobRunId, null);

        if (outcome != Outcome.SIGNAL && outcome != Outcome.BLOCKED_BY_AI) {
            plain.add(evaluationRow);
            return new Result(e, outcome, false);
        }
        EntrySignalRow signal = signalRow(row, e, risk, role, origin, expiresOn, jobRunId,
                outcome == Outcome.BLOCKED_BY_AI ? "VETOED" : "NEW", aiOutcome);
        double close = (Double) e.indicators().get("close");
        double atr = (Double) e.indicators().get("atr14");
        Double zoneBottom = e.hitZone() == null ? null : e.hitZone().bottom();
        Map<String, BigDecimal> stops = new LinkedHashMap<>();
        VARIANTS.forEach((variant, multiple) -> stops.put(variant,
                "BASE".equals(variant) ? signal.stop() : scaled(SignalTrades.stop(close, atr, zoneBottom, multiple, th))));
        Long id = store.save(evaluationRow, signal, stops);
        if (id != null && aiOutcome != null) {
            ai.linkSignal(aiOutcome.analysisId(), id);
        }
        return new Result(e, outcome, id != null);
    }

    /** 上一交易日状态：有正常判定的评估行就用它；没有（刚上线、漏跑）或当天不予判定时现场重算四门，AI 按未否决。 */
    PreviousDay previousDay(SignalEvaluationRow stored, List<DailyBar> raw, List<RehabFactor> factors, Set<LocalDate> calendar,
                            LocalDate previousDay) {
        if (stored != null && SentinelEvaluation.Status.EVALUATED.name().equals(stored.status())) {
            if (!"PPPP".equals(stored.gates())) {
                return PreviousDay.NOT_PASSED;
            }
            return Outcome.BLOCKED_BY_AI.name().equals(stored.outcome()) ? PreviousDay.BLOCKED_BY_AI : PreviousDay.PASSED;
        }
        return SignalInputs.judge(raw, factors, calendar, previousDay, th).allPassed() ? PreviousDay.PASSED : PreviousDay.NOT_PASSED;
    }

    /** 上一条信号到判定日相隔的交易日数；没有信号或早于日历起点（远超冷却期）时 null。 */
    static Integer tradingDaysSince(List<LocalDate> calendar, LocalDate lastSignal, LocalDate asOf) {
        if (lastSignal == null) {
            return null;
        }
        int from = calendar.indexOf(lastSignal);
        int to = calendar.indexOf(asOf);
        return from < 0 || to < 0 ? null : to - from;
    }

    record AiContext(boolean enabled, java.time.Instant deadline) {
        static final AiContext OFF = new AiContext(false, null);
    }

    private EntrySignalRow signalRow(InstrumentRow row, SentinelEvaluation e, GateResult risk, String role, String origin,
                                     LocalDate expiresOn, Long jobRunId, String status, AiVetoService.Outcome aiOutcome) {
        ExitPlan plan = e.exitPlan();
        return new EntrySignalRow(0, row.id(), row.symbol(), e.asOf(), th.version(), role, origin,
                num(e.indicators().get("close")), num(e.indicators().get("atr14")), num(risk.values().get("stop")),
                (String) risk.values().get("stopLeg"), num(risk.values().get("stopDistance")), scaled(plan.riskPerShare()),
                scaled(plan.plusOneR()), scaled(plan.chandelierStop()), plan.target() == null ? null : scaled(plan.target()),
                plan.rewardRisk() == null ? null : scaled(plan.rewardRisk()),
                e.hitZone() == null ? null : scaled(e.hitZone().bottom()), e.hitZone() == null ? null : scaled(e.hitZone().top()),
                e.hitZone() == null ? null : e.hitZone().touches(), write(e.bonus()),
                aiOutcome == null ? null : aiOutcome.analysisId(), aiOutcome == null ? null : aiOutcome.stance(), status, expiresOn, null, null,
                jobRunId, null);
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("判定明细序列化失败：" + ex.getMessage(), ex);
        }
    }

    static String gates(SentinelEvaluation e) {
        return e.gates().isEmpty() ? null
                : e.gates().stream().map(g -> g.verdict().name().substring(0, 1)).reduce("", String::concat);
    }

    static String roleOf(PoolRole role) {
        return role == PoolRole.POOL || role == PoolRole.HOLDING ? role.name() : "UNIVERSE";
    }

    private static BigDecimal num(Object v) {
        return v == null ? null : scaled(((Number) v).doubleValue());
    }

    private static BigDecimal scaled(double v) {
        return BigDecimal.valueOf(v).setScale(6, java.math.RoundingMode.HALF_UP);
    }

    private static LocalDate weekdaysAfter(LocalDate d, int n) {
        LocalDate x = d;
        while (n > 0) {
            x = x.plusDays(1);
            if (x.getDayOfWeek().getValue() <= 5) {
                n--;
            }
        }
        return x;
    }

    private static String describe(Map<? extends Enum<?>, Integer> counts) {
        return counts.isEmpty() ? "无" : counts.entrySet().stream().map(en -> en.getKey().name() + " " + en.getValue())
                .reduce((a, b) -> a + "、" + b).orElse("");
    }
}
