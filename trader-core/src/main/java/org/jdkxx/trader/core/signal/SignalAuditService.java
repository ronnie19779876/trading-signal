package org.jdkxx.trader.core.signal;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jdkxx.trader.core.marketdata.Jobs;
import org.jdkxx.trader.core.marketdata.audit.BarAuditService.Check;
import org.jdkxx.trader.core.marketdata.audit.BarAuditService.Report;
import org.jdkxx.trader.core.marketdata.bars.DailyIncrementService;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.signal.SentinelThresholds;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.jdkxx.trader.storage.marketdata.JobRunRepository;
import org.jdkxx.trader.storage.marketdata.JobRunRow;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;
import org.jdkxx.trader.storage.signal.EntrySignalRepository;
import org.jdkxx.trader.storage.signal.EntrySignalRow;
import org.jdkxx.trader.storage.signal.SignalEvaluationRepository;
import org.jdkxx.trader.storage.signal.SignalEvaluationRow;
import org.jdkxx.trader.storage.signal.SignalTrackRepository;

/**
 * Signal audit, section five of the post-close daily check ({@code check-daily.sh}).
 *
 * <p>The evaluation is scheduled at 18:10 US/Eastern and the daily check often runs around 18:30: a
 * missing evaluation before 19:00 is only a notice; after that it fails as a critical check.
 * Critical checks: the day's evaluation exists, coverage is complete (every target valid on day d
 * has an evaluation), and the open paper ledger is computed through the day. Informational checks:
 * stale-data skips above 2%, instruments skipped for missing days or failed basis conversion,
 * signals that disagree with the recomputed verdict, and the latest job run.
 */
public class SignalAuditService {

  static final LocalTime EVALUATION_DUE = LocalTime.of(19, 0);
  static final double STALE_RATIO = 0.02;

  private final SignalEvaluationService evaluation;
  private final SignalEvaluationRepository evaluations;
  private final EntrySignalRepository signals;
  private final SignalTrackRepository tracks;
  private final TradingDayRepository days;
  private final JobRunRepository jobs;
  private final org.jdkxx.trader.storage.signal.AiAnalysisRepository analyses;
  private final Clock clock;
  private final ZoneId zone;
  private final String version = SentinelThresholds.V1.version();

  public SignalAuditService(
      SignalEvaluationService evaluation,
      SignalEvaluationRepository evaluations,
      EntrySignalRepository signals,
      SignalTrackRepository tracks,
      TradingDayRepository days,
      JobRunRepository jobs,
      org.jdkxx.trader.storage.signal.AiAnalysisRepository analyses,
      Clock clock,
      ZoneId zone) {
    this.analyses = analyses;
    this.evaluation = evaluation;
    this.evaluations = evaluations;
    this.signals = signals;
    this.tracks = tracks;
    this.days = days;
    this.jobs = jobs;
    this.clock = clock;
    this.zone = zone;
  }

  public Report audit(LocalDate date) {
    ZonedDateTime now = ZonedDateTime.now(clock.withZone(zone));
    LocalDate d = date != null ? date : expectedDate(now);
    List<Check> checks = new ArrayList<>();
    Map<String, Object> summary = new LinkedHashMap<>();
    if (days.covers(Market.US, d) && !days.isTradingDay(Market.US, d)) {
      summary.put("tradingDay", false);
      checks.add(new Check("calendar", true, true, d + " 美股休市，当天没有信号评估", 0, List.of()));
      return new Report(d, true, clock.instant(), summary, checks);
    }
    summary.put("tradingDay", true);
    boolean due = now.isAfter(d.atTime(EVALUATION_DUE).atZone(zone));

    List<SignalEvaluationRow> rows = evaluations.on(d, version);
    // Denominator is the targets valid on day d, not the current list (after the weekend sync
    // changed constituents, auditing 10-09 before Monday's close reported falsely, 2026-10-12)
    Map<Long, InstrumentRow> expected = evaluation.targetsOn(d);
    int targets = expected.size();
    summary.put("targets", targets);
    summary.put("evaluations", rows.size());
    if (rows.isEmpty()) {
      checks.add(
          new Check(
              "evaluationExists",
              false,
              due,
              due
                  ? d + " 没有信号评估：看 SIGNAL_EVALUATION 作业（22:00 补偿检查会补跑）"
                  : d + " 的信号评估定于美东 18:10，还没到核对时点（19:00），先不判失败",
              0,
              List.of()));
    } else {
      checks.add(
          new Check(
              "evaluationExists",
              true,
              true,
              d + " 有 " + rows.size() + " 条评估",
              rows.size(),
              List.of()));
      Set<Long> evaluated =
          rows.stream().map(SignalEvaluationRow::instrumentId).collect(Collectors.toSet());
      List<String> missing =
          expected.values().stream()
              .filter(r -> !evaluated.contains(r.id()))
              .map(InstrumentRow::symbol)
              .sorted()
              .toList();
      checks.add(
          new Check(
              "coverage",
              missing.isEmpty(),
              due,
              missing.isEmpty()
                  ? "评估覆盖 " + d + " 当天的全部 " + targets + " 只目标（评估 " + rows.size() + " 条）"
                  : d + " 当天的 " + targets + " 只目标里缺 " + missing.size() + " 只没有评估",
              missing.size(),
              head(missing)));

      Map<String, List<SignalEvaluationRow>> byStatus =
          rows.stream().collect(Collectors.groupingBy(SignalEvaluationRow::status));
      byStatus.forEach((k, v) -> summary.put(k, v.size()));
      List<String> stale = symbols(byStatus.get("SKIPPED_STALE_DATA"));
      checks.add(
          new Check(
              "staleData",
              stale.size() <= targets * STALE_RATIO,
              false,
              stale.isEmpty()
                  ? "没有因数据过期跳过的标的"
                  : stale.size()
                      + " 只因判定日没有 K 线跳过"
                      + (stale.size() > targets * STALE_RATIO ? "（超过 2%，看当天增量）" : ""),
              stale.size(),
              head(stale)));
      List<String> other = new ArrayList<>(symbols(byStatus.get("SKIPPED_DATA_GAP")));
      other.addAll(symbols(byStatus.get("SKIPPED_CORPORATE_ACTION")));
      checks.add(
          new Check(
              "dataQuality",
              other.isEmpty(),
              false,
              other.isEmpty() ? "没有因缺日或公司行动口径跳过的标的" : other.size() + " 只因缺日或公司行动比例缺失跳过",
              other.size(),
              head(other)));

      List<EntrySignalRow> daySignals = signals.on(d, version);
      summary.put("signals", daySignals.size());
      summary.put(
          "poolSignals", daySignals.stream().filter(s -> !"UNIVERSE".equals(s.role())).count());
      Map<Long, SignalEvaluationRow> byInstrument =
          rows.stream().collect(Collectors.toMap(SignalEvaluationRow::instrumentId, r -> r));
      List<String> mismatched =
          daySignals.stream()
              .filter(s -> !consistent(byInstrument.get(s.instrumentId())))
              .map(EntrySignalRow::symbol)
              .toList();
      checks.add(
          new Check(
              "signalConsistency",
              mismatched.isEmpty(),
              false,
              mismatched.isEmpty()
                  ? daySignals.size() + " 条信号与当天评估结论一致"
                  : mismatched.size() + " 条信号与重算后的评估结论不一致（多半是 K 线被订正过，信号保留）",
              mismatched.size(),
              head(mismatched)));

      List<org.jdkxx.trader.storage.signal.AiAnalysisRow> ai =
          analyses.onTradeDate(d).stream().filter(a -> "SIGNAL_VETO".equals(a.purpose())).toList();
      summary.put("aiAnalyses", ai.size());
      summary.put("aiVetoes", ai.stream().filter(a -> "VETO".equals(a.verdict())).count());
      List<String> aiProblems =
          ai.stream()
              .filter(a -> !"OK".equals(a.status()))
              .map(a -> a.symbol() + " " + a.status() + (a.error() == null ? "" : "：" + a.error()))
              .toList();
      int unverified = ai.stream().mapToInt(a -> a.unverified() == null ? 0 : a.unverified()).sum();
      checks.add(
          new Check(
              "aiAnalyses",
              aiProblems.isEmpty(),
              false,
              ai.isEmpty()
                  ? "当天没有调用模型（没有候选，或未开启 / 补跑）"
                  : ai.size()
                      + " 次模型分析，"
                      + aiProblems.size()
                      + " 次没有结论（失败、拒答、截断或预算跳过，均按放行处理）"
                      + (unverified > 0 ? "；" + unverified + " 条证据核对不通过" : ""),
              aiProblems.size(),
              head(aiProblems)));

      long staleTracks = tracks.staleUnfinished(d);
      checks.add(
          new Check(
              "ledgerCurrent",
              staleTracks == 0,
              due,
              staleTracks == 0 ? "未平仓的纸面账本都已算到 " + d : staleTracks + " 条未平仓账本还没算到 " + d,
              staleTracks,
              List.of()));
    }

    Optional<JobRunRow> last = jobs.latestOf(Jobs.SIGNAL_EVALUATION);
    checks.add(
        new Check(
            "evaluationJob",
            last.map(j -> "OK".equals(j.status())).orElse(false),
            false,
            last.map(j -> "最近一次信号评估作业 #" + j.id() + " " + j.status() + "（" + j.startedAt() + "）")
                .orElse("还没有跑过信号评估作业"),
            last.isPresent() ? 1 : 0,
            List.of()));

    boolean ok = checks.stream().filter(Check::critical).allMatch(Check::ok);
    return new Report(d, ok, clock.instant(), summary, checks);
  }

  /**
   * Whether the evaluation verdict is consistent with "a signal exists".
   *
   * <p>{@code outcome} is <b>stored as NULL</b> on skipped evaluations (see {@code
   * SignalEvaluationService}: {@code outcome == null ? null : outcome.name()}), and the whole point
   * of this check is to report "the signal is still there but the evaluation verdict has changed" -
   * and the most common such inconsistency is precisely a re-run ending up SKIPPED. It used to be
   * written as {@code Set.of("SIGNAL","BLOCKED_BY_AI").contains(r.outcome())}, and {@code
   * Set.of(...).contains(null)} throws NPE: the whole audit returned 500 and section five of the
   * daily check died with it (found in the 2026-09-25 full project review; at the time the 24
   * production rows with a NULL outcome were all SKIPPED_INSUFFICIENT_BARS with no matching signal,
   * so it had never triggered).
   */
  private static boolean consistent(SignalEvaluationRow r) {
    return r != null && ("SIGNAL".equals(r.outcome()) || "BLOCKED_BY_AI".equals(r.outcome()));
  }

  private static List<String> symbols(List<SignalEvaluationRow> rows) {
    return rows == null
        ? List.of()
        : rows.stream().map(SignalEvaluationRow::symbol).sorted().toList();
  }

  private static List<String> head(List<String> list) {
    return list.size() <= 20 ? list : list.subList(0, 20);
  }

  /**
   * Default audit date: the latest trading day whose bars are expected to be in by now
   * (US/Eastern).
   */
  LocalDate expectedDate(ZonedDateTime now) {
    LocalDate today = now.toLocalDate();
    LocalDate d =
        DailyIncrementService.expectedLatestTradingDay(
            days.between(Market.US, today.minusDays(45), today), now);
    return d != null ? d : today;
  }
}
