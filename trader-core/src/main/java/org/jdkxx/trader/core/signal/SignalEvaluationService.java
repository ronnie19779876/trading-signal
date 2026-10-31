package org.jdkxx.trader.core.signal;

import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * Job body of the daily entry-sentinel evaluation ({@code SIGNAL_EVALUATION}): all index
 * constituents plus pool and holdings, minus benchmarks.
 *
 * <p>Per instrument: data-layer checks, then the four gates, then the edge (the previous trading
 * day's evaluation row; when there is none, or that day was not judgeable, it is recomputed on the
 * spot with the same rules, treating unknown AI information as not vetoed), then the cooldown
 * (trading days since the last non-vetoed signal), then AI (step 3 always treated as no verdict);
 * the evaluation row, the signal and the ledger rows of both exit variants are written in one
 * transaction. Wrap-up: expire old signals and recompute the open ledger rows.
 *
 * <p>Re-running the same day: evaluations are overwritten, signals already issued are left alone
 * (blocked by the unique key); any that are no longer signals after the recompute are flagged by
 * the audit. When back-filling a past date the signals are marked BACKFILL and kept out of the live
 * statistics.
 */
public class SignalEvaluationService {

  private static final Logger log = LoggerFactory.getLogger(SignalEvaluationService.class);

  /** Ledger variant to stop ATR multiple. BASE is the sentinel-v1 exit. */
  static final Map<String, Double> VARIANTS = Map.of("BASE", 2.0, "STOP_2_5", 2.5);

  /** Instruments per bulk read / bulk evaluation write */
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

  public SignalEvaluationService(
      UniverseScope scope,
      DailyBarRepository bars,
      RehabFactorRepository rehabs,
      TradingDayRepository days,
      SignalEvaluationRepository evaluations,
      EntrySignalRepository signals,
      SignalStore store,
      SignalLedgerService ledger,
      SettledCutoff cutoff,
      ObjectMapper json,
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
   * Evaluation targets: all index constituents plus pool and holdings, minus benchmarks.
   *
   * <p>The order is the order in which the AI budget is handed out: the model is called while the
   * job evaluates, so the daily cap and the in-job time budget are first come, first served. Hence
   * holdings first, then pool, then everything else, by symbol within each role. In 3.0.0
   * constituents came first in symbol order and pool / holdings mostly kept their place among them,
   * so the budget went out alphabetically - on quadruple witching (2026-09-18) there were 51
   * candidates, the budget ran out at the G's, and holding IBKR was skipped for budget.
   */
  Map<Long, InstrumentRow> targets() {
    return targets(scope.universe());
  }

  /**
   * Targets that should have been evaluated on a given day: constituents valid on that day plus the
   * pool (the pool has no history, so the current one is used) minus benchmarks. Used by the audit
   * coverage check - after the weekend sync changes the constituents, the current list differs from
   * the one used on the previous trading day (2026-10-12 measured: 3 added and 2 removed on 10-10;
   * comparing the 518 evaluations of 10-09 against today's 519 names falsely reported "fewer than
   * targets").
   */
  Map<Long, InstrumentRow> targetsOn(LocalDate d) {
    return targets(scope.universeOn(d));
  }

  private Map<Long, InstrumentRow> targets(List<InstrumentRow> universe) {
    Map<Long, InstrumentRow> byId = new HashMap<>();
    universe.forEach(r -> byId.put(r.id(), r));
    scope.candidates().forEach(r -> byId.put(r.id(), r));
    scope.benchmarks().forEach(r -> byId.remove(r.id()));
    Map<Long, PoolRole> roles = scope.roles();
    Map<Long, InstrumentRow> ordered = new LinkedHashMap<>();
    byId.values().stream()
        .sorted(
            Comparator.comparingInt((InstrumentRow r) -> aiPriority(roles.get(r.id())))
                .thenComparing(InstrumentRow::symbol))
        .forEach(r -> ordered.put(r.id(), r));
    return ordered;
  }

  /** Holdings 0, pool 1, everything else 2. */
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
    // Calendar spans the whole look-back window (for the cooldown count) plus 14 days ahead (for
    // the expiry date)
    List<LocalDate> calendar =
        days.between(Market.US, asOf.minusDays(th.windowCalendarDays() + 10), asOf.plusDays(14));
    int at = calendar.indexOf(asOf);
    if (at < 1) {
      throw new IllegalArgumentException(asOf + " 不是交易日（或交易日历不覆盖）");
    }
    LocalDate previousDay = calendar.get(at - 1);
    // Expiry = the validityDays-th trading day after the judgement date; if the calendar does not
    // reach that far yet, estimate by counting weekdays
    LocalDate expiresOn =
        at + th.validityDays() < calendar.size()
            ? calendar.get(at + th.validityDays())
            : weekdaysAfter(asOf, th.validityDays());
    Set<LocalDate> calendarSet = new HashSet<>(calendar);

    Map<Long, InstrumentRow> targets = targets();
    boolean aiOn = ai != null && ai.enabledFor(origin);
    AiContext aiContext = new AiContext(aiOn, aiOn ? ai.jobDeadline() : null);
    Map<Long, PoolRole> roles = scope.roles();
    Map<Long, SignalEvaluationRow> previous = evaluations.byInstrumentOn(previousDay, th.version());
    Map<Long, LocalDate> lastSignals = signals.lastSignalDatesBefore(asOf, th.version());

    Map<SentinelEvaluation.Status, Integer> statuses =
        new EnumMap<>(SentinelEvaluation.Status.class);
    Map<Outcome, Integer> outcomes = new EnumMap<>(Outcome.class);
    int created = 0;
    int poolSignals = 0;
    int failed = 0;
    int done = 0;
    List<InstrumentRow> all = List.copyOf(targets.values());
    // Fetch and write evaluation rows in batches: from the dev instance over the tunnel, one round
    // trip per instrument took more than four minutes
    for (int start = 0; start < all.size() && !ctx.cancelled(); start += BATCH) {
      List<InstrumentRow> batch = all.subList(start, Math.min(all.size(), start + BATCH));
      Map<Long, org.jdkxx.trader.domain.Instrument> instruments = new LinkedHashMap<>();
      batch.forEach(r -> instruments.put(r.id(), r.instrument()));
      // Bars start one look-back window before the previous trading day: the edge may need to
      // recompute that day
      Map<Long, List<DailyBar>> barsById =
          bars.findMany(instruments, previousDay.minusDays(th.windowCalendarDays()), asOf);
      Map<Long, List<RehabFactor>> factorsById = rehabs.findMany(instruments);
      List<SignalEvaluationRow> plain = new ArrayList<>();
      for (InstrumentRow row : batch) {
        try {
          String role = roleOf(roles.get(row.id()));
          Result r =
              evaluate(
                  row,
                  barsById.getOrDefault(row.id(), List.of()),
                  factorsById.getOrDefault(row.id(), List.of()),
                  asOf,
                  previousDay,
                  calendarSet,
                  calendar,
                  previous.get(row.id()),
                  lastSignals.get(row.id()),
                  role,
                  origin,
                  expiresOn,
                  ctx.id(),
                  plain,
                  aiContext);
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
    return "评估 "
        + asOf
        + (origin.equals("BACKFILL") ? "（补跑）" : "")
        + "：目标 "
        + targets.size()
        + " 只，"
        + describe(statuses)
        + "；结果 "
        + describe(outcomes)
        + "；新信号 "
        + created
        + " 条（池与持仓 "
        + poolSignals
        + "）；过期 "
        + expired
        + " 条；账本重算 "
        + l.updated()
        + " 条、本次平仓 "
        + l.closed()
        + " 条";
  }

  record Result(SentinelEvaluation evaluation, Outcome outcome, boolean createdSignal) {}

  /**
   * @param raw bars covering the previous trading day's window: the edge may have to recompute the
   *     previous trading day
   * @param plain non-signal evaluation rows are collected here and written in bulk by the caller;
   *     signal rows are written immediately in one transaction together with the signal and its
   *     ledger rows
   */
  Result evaluate(
      InstrumentRow row,
      List<DailyBar> raw,
      List<RehabFactor> factors,
      LocalDate asOf,
      LocalDate previousDay,
      Set<LocalDate> calendarSet,
      List<LocalDate> calendar,
      SignalEvaluationRow previousRow,
      LocalDate lastSignal,
      String role,
      String origin,
      LocalDate expiresOn,
      Long jobRunId,
      List<SignalEvaluationRow> plain,
      AiContext aiContext) {
    SignalInputs.Prepared p = SignalInputs.prepare(raw, factors, calendarSet, asOf, th);
    SentinelEvaluation e =
        p.skipped() != null ? p.skipped() : SentinelEvaluator.evaluate(p.bars(), asOf, th);

    Outcome outcome = null;
    AiVetoService.Outcome aiOutcome = null;
    if (e.status() == SentinelEvaluation.Status.EVALUATED) {
      PreviousDay prev = previousDay(previousRow, raw, factors, calendarSet, previousDay);
      Integer since = tradingDaysSince(calendar, lastSignal, asOf);
      Outcome beforeAi = SignalSuppression.beforeAi(e.allPassed(), prev, since, th);
      // Only call the model for candidates that passed the edge and the cooldown and would become a
      // signal today
      if (beforeAi == Outcome.PENDING_AI && aiContext.enabled()) {
        aiOutcome = ai.analyze(row, e, "SIGNAL_VETO", jobRunId, aiContext.deadline());
      }
      outcome =
          SignalSuppression.afterAi(
              beforeAi,
              aiOutcome == null ? SignalSuppression.AiVerdict.ABSENT : aiOutcome.verdict());
    }
    // Full detail is kept for pool / holdings, for near misses (3+ gates) and anything that is not
    // NO_SIGNAL; plain universe rows stay small
    boolean keepDetail =
        !"UNIVERSE".equals(role)
            || e.gatesPassed() >= 3
            || (outcome != null && outcome != Outcome.NO_SIGNAL);
    Map<String, Object> detail = null;
    if (keepDetail) {
      detail = new LinkedHashMap<>();
      detail.put("evaluation", e);
      detail.put("droppedNonTradingDays", p.droppedNonTradingDays());
      detail.put("missingTradingDays", p.missingTradingDays());
    }
    GateResult risk =
        e.gates().stream().filter(g -> g.gate() == GateResult.Gate.RISK).findFirst().orElse(null);
    SignalEvaluationRow evaluationRow =
        new SignalEvaluationRow(
            row.id(),
            row.symbol(),
            asOf,
            th.version(),
            e.status().name(),
            e.statusDetail(),
            outcome == null ? null : outcome.name(),
            gates(e),
            e.gatesPassed(),
            e.firstBlockingGate().map(Enum::name).orElse(null),
            role,
            num(e.indicators().get("close")),
            num(e.indicators().get("atr14")),
            num(e.indicators().get("rvol")),
            e.hitZone() == null ? null : BigDecimal.valueOf(e.hitZone().bottom()),
            risk == null ? null : num(risk.values().get("stop")),
            risk == null ? null : num(risk.values().get("stopDistance")),
            p.fingerprint(),
            detail == null ? null : write(detail),
            jobRunId,
            null);

    if (outcome != Outcome.SIGNAL && outcome != Outcome.BLOCKED_BY_AI) {
      plain.add(evaluationRow);
      return new Result(e, outcome, false);
    }
    EntrySignalRow signal =
        signalRow(
            row,
            e,
            risk,
            role,
            origin,
            expiresOn,
            jobRunId,
            outcome == Outcome.BLOCKED_BY_AI ? "VETOED" : "NEW",
            aiOutcome);
    double close = (Double) e.indicators().get("close");
    double atr = (Double) e.indicators().get("atr14");
    Double zoneBottom = e.hitZone() == null ? null : e.hitZone().bottom();
    // Each ledger variant gets its own stop; BASE reuses the signal's stop so both are exactly the
    // same number
    Map<String, BigDecimal> stops = new LinkedHashMap<>();
    VARIANTS.forEach(
        (variant, multiple) ->
            stops.put(
                variant,
                "BASE".equals(variant)
                    ? signal.stop()
                    : scaled(SignalTrades.stop(close, atr, zoneBottom, multiple, th))));
    // store.save returns null when the unique key already holds a signal for this day (re-run):
    // nothing to link
    Long id = store.save(evaluationRow, signal, stops);
    if (id != null && aiOutcome != null) {
      ai.linkSignal(aiOutcome.analysisId(), id);
    }
    return new Result(e, outcome, id != null);
  }

  /**
   * Previous trading day's state: use its evaluation row when it was judged normally; otherwise
   * (just went live, a missed run) or when that day was not judgeable, recompute the four gates on
   * the spot and treat AI as not vetoed.
   */
  PreviousDay previousDay(
      SignalEvaluationRow stored,
      List<DailyBar> raw,
      List<RehabFactor> factors,
      Set<LocalDate> calendar,
      LocalDate previousDay) {
    if (stored != null && SentinelEvaluation.Status.EVALUATED.name().equals(stored.status())) {
      if (!"PPPP".equals(stored.gates())) {
        return PreviousDay.NOT_PASSED;
      }
      return Outcome.BLOCKED_BY_AI.name().equals(stored.outcome())
          ? PreviousDay.BLOCKED_BY_AI
          : PreviousDay.PASSED;
    }
    return SignalInputs.judge(raw, factors, calendar, previousDay, th).allPassed()
        ? PreviousDay.PASSED
        : PreviousDay.NOT_PASSED;
  }

  /**
   * Trading days from the last signal to the judgement date; null when there is no signal or it
   * predates the calendar (far beyond the cooldown).
   */
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

  private EntrySignalRow signalRow(
      InstrumentRow row,
      SentinelEvaluation e,
      GateResult risk,
      String role,
      String origin,
      LocalDate expiresOn,
      Long jobRunId,
      String status,
      AiVetoService.Outcome aiOutcome) {
    ExitPlan plan = e.exitPlan();
    return new EntrySignalRow(
        0,
        row.id(),
        row.symbol(),
        e.asOf(),
        th.version(),
        role,
        origin,
        num(e.indicators().get("close")),
        num(e.indicators().get("atr14")),
        num(risk.values().get("stop")),
        (String) risk.values().get("stopLeg"),
        num(risk.values().get("stopDistance")),
        scaled(plan.riskPerShare()),
        scaled(plan.plusOneR()),
        scaled(plan.chandelierStop()),
        plan.target() == null ? null : scaled(plan.target()),
        plan.rewardRisk() == null ? null : scaled(plan.rewardRisk()),
        e.hitZone() == null ? null : scaled(e.hitZone().bottom()),
        e.hitZone() == null ? null : scaled(e.hitZone().top()),
        e.hitZone() == null ? null : e.hitZone().touches(),
        write(e.bonus()),
        aiOutcome == null ? null : aiOutcome.analysisId(),
        aiOutcome == null ? null : aiOutcome.stance(),
        status,
        expiresOn,
        null,
        null,
        jobRunId,
        null);
  }

  private String write(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception ex) {
      throw new IllegalStateException("判定明细序列化失败：" + ex.getMessage(), ex);
    }
  }

  /**
   * Gate verdicts as one letter each in gate order (P pass, F fail, U undecidable), e.g. "PPPP";
   * null when not judged.
   */
  static String gates(SentinelEvaluation e) {
    return e.gates().isEmpty()
        ? null
        : e.gates().stream()
            .map(g -> g.verdict().name().substring(0, 1))
            .reduce("", String::concat);
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
    return counts.isEmpty()
        ? "无"
        : counts.entrySet().stream()
            .map(en -> en.getKey().name() + " " + en.getValue())
            .reduce((a, b) -> a + "、" + b)
            .orElse("");
  }
}
