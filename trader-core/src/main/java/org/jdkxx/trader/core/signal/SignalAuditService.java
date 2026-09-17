package org.jdkxx.trader.core.signal;

import org.jdkxx.trader.core.marketdata.Jobs;
import org.jdkxx.trader.core.marketdata.audit.BarAuditService.Check;
import org.jdkxx.trader.core.marketdata.audit.BarAuditService.Report;
import org.jdkxx.trader.core.marketdata.bars.DailyIncrementService;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.signal.SentinelThresholds;
import org.jdkxx.trader.storage.marketdata.JobRunRepository;
import org.jdkxx.trader.storage.marketdata.JobRunRow;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;
import org.jdkxx.trader.storage.signal.EntrySignalRepository;
import org.jdkxx.trader.storage.signal.EntrySignalRow;
import org.jdkxx.trader.storage.signal.SignalEvaluationRepository;
import org.jdkxx.trader.storage.signal.SignalEvaluationRow;
import org.jdkxx.trader.storage.signal.SignalTrackRepository;

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

/**
 * 信号审计，收盘巡检第五段（{@code check-daily.sh}）。
 *
 * <p>评估定在美东 18:10，巡检常在 18:30 前后跑：19:00 前缺评估只提示，过了才判关键项失败。
 * 关键项：当天评估存在、覆盖完整（评估行数 = 目标数）、未平仓账本算到了当天。
 * 提示项：过期跳过占比 &gt; 2%、缺日与口径换算失败的标的、当天信号与重算结论不一致、最近一次作业。
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
    private final Clock clock;
    private final ZoneId zone;
    private final String version = SentinelThresholds.V1.version();

    public SignalAuditService(SignalEvaluationService evaluation, SignalEvaluationRepository evaluations, EntrySignalRepository signals,
                              SignalTrackRepository tracks, TradingDayRepository days, JobRunRepository jobs, Clock clock, ZoneId zone) {
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
        int targets = evaluation.targets().size();
        summary.put("targets", targets);
        summary.put("evaluations", rows.size());
        if (rows.isEmpty()) {
            checks.add(new Check("evaluationExists", false, due,
                    due ? d + " 没有信号评估：看 SIGNAL_EVALUATION 作业（22:00 补偿检查会补跑）"
                            : d + " 的信号评估定于美东 18:10，还没到核对时点（19:00），先不判失败", 0, List.of()));
        } else {
            checks.add(new Check("evaluationExists", true, true, d + " 有 " + rows.size() + " 条评估", rows.size(), List.of()));
            checks.add(new Check("coverage", rows.size() >= targets, due,
                    rows.size() >= targets ? "评估覆盖全部 " + targets + " 只目标" : "评估 " + rows.size() + " 条，少于目标 " + targets + " 只",
                    Math.max(0, targets - rows.size()), List.of()));

            Map<String, List<SignalEvaluationRow>> byStatus = rows.stream().collect(Collectors.groupingBy(SignalEvaluationRow::status));
            byStatus.forEach((k, v) -> summary.put(k, v.size()));
            List<String> stale = symbols(byStatus.get("SKIPPED_STALE_DATA"));
            checks.add(new Check("staleData", stale.size() <= targets * STALE_RATIO, false,
                    stale.isEmpty() ? "没有因数据过期跳过的标的" : stale.size() + " 只因判定日没有 K 线跳过"
                            + (stale.size() > targets * STALE_RATIO ? "（超过 2%，看当天增量）" : ""), stale.size(), head(stale)));
            List<String> other = new ArrayList<>(symbols(byStatus.get("SKIPPED_DATA_GAP")));
            other.addAll(symbols(byStatus.get("SKIPPED_CORPORATE_ACTION")));
            checks.add(new Check("dataQuality", other.isEmpty(), false,
                    other.isEmpty() ? "没有因缺日或公司行动口径跳过的标的" : other.size() + " 只因缺日或公司行动比例缺失跳过",
                    other.size(), head(other)));

            List<EntrySignalRow> daySignals = signals.on(d, version);
            summary.put("signals", daySignals.size());
            summary.put("poolSignals", daySignals.stream().filter(s -> !"UNIVERSE".equals(s.role())).count());
            Map<Long, SignalEvaluationRow> byInstrument = rows.stream()
                    .collect(Collectors.toMap(SignalEvaluationRow::instrumentId, r -> r));
            List<String> mismatched = daySignals.stream()
                    .filter(s -> {
                        SignalEvaluationRow r = byInstrument.get(s.instrumentId());
                        return r == null || !Set.of("SIGNAL", "BLOCKED_BY_AI").contains(r.outcome());
                    })
                    .map(EntrySignalRow::symbol).toList();
            checks.add(new Check("signalConsistency", mismatched.isEmpty(), false,
                    mismatched.isEmpty() ? daySignals.size() + " 条信号与当天评估结论一致"
                            : mismatched.size() + " 条信号与重算后的评估结论不一致（多半是 K 线被订正过，信号保留）",
                    mismatched.size(), head(mismatched)));

            long staleTracks = tracks.staleUnfinished(d);
            checks.add(new Check("ledgerCurrent", staleTracks == 0, due,
                    staleTracks == 0 ? "未平仓的纸面账本都已算到 " + d : staleTracks + " 条未平仓账本还没算到 " + d,
                    staleTracks, List.of()));
        }

        Optional<JobRunRow> last = jobs.latestOf(Jobs.SIGNAL_EVALUATION);
        checks.add(new Check("evaluationJob", last.map(j -> "OK".equals(j.status())).orElse(false), false,
                last.map(j -> "最近一次信号评估作业 #" + j.id() + " " + j.status() + "（" + j.startedAt() + "）")
                        .orElse("还没有跑过信号评估作业"), last.isPresent() ? 1 : 0, List.of()));

        boolean ok = checks.stream().filter(Check::critical).allMatch(Check::ok);
        return new Report(d, ok, clock.instant(), summary, checks);
    }

    private static List<String> symbols(List<SignalEvaluationRow> rows) {
        return rows == null ? List.of() : rows.stream().map(SignalEvaluationRow::symbol).sorted().toList();
    }

    private static List<String> head(List<String> list) {
        return list.size() <= 20 ? list : list.subList(0, 20);
    }

    LocalDate expectedDate(ZonedDateTime now) {
        LocalDate today = now.toLocalDate();
        LocalDate d = DailyIncrementService.expectedLatestTradingDay(days.between(Market.US, today.minusDays(45), today), now);
        return d != null ? d : today;
    }
}
