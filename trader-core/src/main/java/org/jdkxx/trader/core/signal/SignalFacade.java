package org.jdkxx.trader.core.signal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;
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

/**
 * Entry point for entry signals: submit evaluation jobs, query signals / evaluations / ledger,
 * change signal status.
 */
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

  public SignalFacade(
      JobService jobs,
      SignalEvaluationService evaluation,
      SentinelService sentinel,
      SignalEvaluationRepository evaluations,
      EntrySignalRepository signals,
      SignalTrackRepository tracks,
      InstrumentDirectory directory,
      TradingDayRepository days,
      SettledCutoff cutoff,
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

  /**
   * Submits an evaluation job. date defaults to the settled date; later than that is a 409 and a
   * non-trading day a 400, both rejected before submitting.
   */
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

  public record SignalView(EntrySignalRow signal, SignalTrackRow base) {}

  public List<SignalView> list(
      LocalDate from, LocalDate to, Set<String> statuses, boolean poolOnly, String origin) {
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
   * @param fingerprintMatches whether the fingerprint recomputed from the current database matches
   *     the stored one; a mismatch means bars or factors were refetched and changed
   * @param recomputed result recomputed on the spot when the stored row has no judgement detail
   *     (null when it has)
   */
  public record SignalDetail(
      EntrySignalRow signal,
      SignalEvaluationRow evaluation,
      boolean fingerprintMatches,
      SentinelService.Judgement recomputed,
      List<SignalTrackRow> tracks) {}

  public SignalDetail detail(long id) {
    EntrySignalRow s =
        signals.find(id).orElseThrow(() -> new NoSuchElementException("信号 #" + id + " 不存在"));
    SignalEvaluationRow e =
        evaluations.find(s.instrumentId(), s.tradeDate(), s.rulesetVersion()).orElse(null);
    SentinelService.Judgement now = sentinel.evaluate(s.symbol(), s.tradeDate());
    boolean matches = e != null && now.fingerprint().equals(e.inputFingerprint());
    return new SignalDetail(
        s, e, matches, e != null && e.detail() != null ? null : now, tracks.bySignal(id));
  }

  /** NEW to ACKNOWLEDGED / DISMISSED; ACKNOWLEDGED to DISMISSED. Anything else is a 409. */
  public EntrySignalRow changeStatus(long id, String to, String note) {
    EntrySignalRow s =
        signals.find(id).orElseThrow(() -> new NoSuchElementException("信号 #" + id + " 不存在"));
    // Allowed source states per target; the transition is a conditional UPDATE, so a concurrent
    // change loses cleanly
    List<String> from =
        switch (to) {
          case "ACKNOWLEDGED" -> List.of("NEW");
          case "DISMISSED" -> List.of("NEW", "ACKNOWLEDGED");
          default -> throw new IllegalArgumentException("status 只能是 ACKNOWLEDGED 或 DISMISSED");
        };
    if (!signals.transition(id, from, to, note)) {
      throw new IllegalStateException("信号 #" + id + " 当前是 " + s.status() + "，不能改为 " + to);
    }
    return signals.find(id).orElseThrow();
  }

  /**
   * All evaluations of one day; outcome / gate filters are optional, poolOnly keeps only pool and
   * holdings.
   */
  public List<SignalEvaluationRow> evaluationsOn(
      LocalDate date, String outcome, String firstBlockingGate, boolean poolOnly) {
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

  /**
   * Ledger summary: stats per variant and origin; paired return difference between BASE and
   * STOP_2_5 over the same set of closed signals.
   *
   * @param ai model verdict group: VETO = vetoed (signal status VETOED), ALLOW = model called and
   *     allowed, NONE = no model verdict (back-fill, disabled, failed, skipped for budget)
   */
  public record LedgerStats(
      String variant,
      String origin,
      String ai,
      int total,
      int open,
      int pending,
      int closed,
      Double winRate,
      Double meanR,
      Double meanReturn) {}

  public record Ledger(
      List<LedgerStats> stats,
      Integer pairedCount,
      Double pairedMeanReturnDiff,
      List<LedgerEntry> entries) {}

  public record LedgerEntry(EntrySignalRow signal, SignalTrackRow track) {}

  public Ledger ledger(String variant, String status) {
    List<SignalTrackRow> all = tracks.list(null, null);
    Map<Long, EntrySignalRow> signalById = new HashMap<>();
    signals
        .findAll(all.stream().map(SignalTrackRow::signalId).distinct().toList())
        .forEach(s -> signalById.put(s.id(), s));
    Map<Long, String> verdicts =
        analyses.verdicts(
            signalById.values().stream()
                .map(EntrySignalRow::aiAnalysisId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList());
    List<LedgerStats> stats = new ArrayList<>();
    Map<List<String>, List<SignalTrackRow>> grouped =
        all.stream()
            .collect(
                Collectors.groupingBy(
                    t -> {
                      EntrySignalRow sig = signalById.get(t.signalId());
                      return List.of(t.variant(), sig.origin(), aiGroup(sig, verdicts));
                    },
                    () ->
                        new java.util.TreeMap<>(
                            java.util.Comparator.comparing(
                                (List<String> k) -> String.join("|", k))),
                    Collectors.toList()));
    grouped.forEach(
        (key, rows) -> {
          List<SignalTrackRow> closed =
              rows.stream().filter(t -> "CLOSED".equals(t.status())).toList();
          stats.add(
              new LedgerStats(
                  key.get(0),
                  key.get(1),
                  key.get(2),
                  rows.size(),
                  count(rows, "OPEN"),
                  count(rows, "PENDING_ENTRY"),
                  closed.size(),
                  closed.isEmpty()
                      ? null
                      : (double) closed.stream().filter(t -> t.rMultiple().signum() > 0).count()
                          / closed.size(),
                  mean(closed.stream().map(SignalTrackRow::rMultiple).toList()),
                  mean(closed.stream().map(SignalTrackRow::returnPct).toList())));
        });
    Map<Long, BigDecimal> base = closedReturns(all, "BASE");
    Map<Long, BigDecimal> wide = closedReturns(all, "STOP_2_5");
    List<BigDecimal> diffs =
        base.entrySet().stream()
            .filter(en -> wide.containsKey(en.getKey()))
            .map(en -> wide.get(en.getKey()).subtract(en.getValue()))
            .toList();
    List<LedgerEntry> entries =
        all.stream()
            .filter(t -> variant == null || variant.equals(t.variant()))
            .filter(t -> status == null || status.equals(t.status()))
            .map(t -> new LedgerEntry(signalById.get(t.signalId()), t))
            .toList();
    return new Ledger(stats, diffs.size(), mean(diffs), entries);
  }

  /**
   * Groups by the verdict of the analysis attached to the signal. Budget skips, failures, refusals
   * and truncations also write an analysis row and attach it to the signal (verdict ABSENT); 3.0.0
   * only checked whether aiAnalysisId was null and counted all of them as ALLOW - on quadruple
   * witching (2026-09-18) 31 signals with no verdict were counted as allowed.
   */
  static String aiGroup(EntrySignalRow s, Map<Long, String> verdicts) {
    if ("VETOED".equals(s.status())) {
      return "VETO";
    }
    return s.aiAnalysisId() != null && "ALLOW".equals(verdicts.get(s.aiAnalysisId()))
        ? "ALLOW"
        : "NONE";
  }

  private static int count(List<SignalTrackRow> rows, String status) {
    return (int) rows.stream().filter(t -> status.equals(t.status())).count();
  }

  private static Map<Long, BigDecimal> closedReturns(List<SignalTrackRow> rows, String variant) {
    return rows.stream()
        .filter(t -> variant.equals(t.variant()) && "CLOSED".equals(t.status()))
        .collect(Collectors.toMap(SignalTrackRow::signalId, SignalTrackRow::returnPct));
  }

  private static Double mean(List<BigDecimal> values) {
    return values.isEmpty()
        ? null
        : values.stream().mapToDouble(BigDecimal::doubleValue).average().orElseThrow();
  }
}
