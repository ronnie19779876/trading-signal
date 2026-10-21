package org.jdkxx.trader.core.selection;

import org.jdkxx.trader.core.marketdata.audit.BarAuditService;
import org.jdkxx.trader.domain.selection.SelectionModel;
import org.jdkxx.trader.storage.selection.SelectionEarningsRepository;
import org.jdkxx.trader.storage.selection.SelectionPriceSeamRepository;
import org.jdkxx.trader.storage.selection.SelectionScoreRepository;
import org.jdkxx.trader.storage.selection.SelectionValuationRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 量化选股审计（§25.7、§25.8）：沿用统一的审计报告结构，巡检脚本第六段读它。关键项不过 → ok=false。
 * 已知偏差原样带出（财报重述口径、幸存者、板块用当前分类、估值水平系统差）。
 */
public class SelectionAuditService {

    /** 可评级只数占范围的下限：低于它多半是输入断了（关键项） */
    static final double MIN_RATED_SHARE = 0.80;
    /** 当天有估值的标的占范围的下限（关键项） */
    static final double MIN_VALUATION_SHARE = 0.90;

    static final List<String> BIASES = List.of(
            "财报是重述后口径（不是当时可见的首次申报值），偏差集中在 2017，影响 2018 年的同比",
            "幸存者偏差：范围是今天的成分股，退市股取不到；回测必须带同期对照",
            "板块用当前 GICS 分类套用全部历史（2023 年改分类不回溯）",
            "富途估值历史与 EDGAR 申报值反推有约 5% 的系统差（组内排名受影响小）");

    private final SelectionTargets targets;
    private final SelectionScoreRepository scores;
    private final SelectionValuationRepository valuations;
    private final SelectionPriceSeamRepository seams;
    private final SelectionSimService sim;
    private final SelectionEarningsRepository earnings;
    private final Supplier<LocalDate> settledCutoff;
    private final Clock clock;

    public SelectionAuditService(SelectionTargets targets, SelectionScoreRepository scores, SelectionValuationRepository valuations,
                                 SelectionPriceSeamRepository seams, SelectionSimService sim, SelectionEarningsRepository earnings,
                                 Supplier<LocalDate> settledCutoff, Clock clock) {
        this.targets = targets;
        this.earnings = earnings;
        this.scores = scores;
        this.valuations = valuations;
        this.seams = seams;
        this.sim = sim;
        this.settledCutoff = settledCutoff;
        this.clock = clock;
    }

    /** 回看多久的公告来找「超期仍不齐」：一个季度再多一点，免得把上上季的旧账也翻出来。 */
    static final int OVERDUE_WINDOW_DAYS = 135;

    /**
     * 报表到齐（3.4.6，非关键）：公告后 {@value SelectionDailyService#COMPLETE_LOOKBACK_DAYS} 天内还缺的由每日作业续取，
     * 这里只报个数；超过这个天数仍缺的（富途一直没给，已不再续取）逐只列出缺哪几类。
     * 缺的季度在评分里会让 ROE / 自由现金流率等指标缺失（2026-10-21 ABT、LVS 因此未评级）。
     */
    BarAuditService.Check financialsComplete(LocalDate d, java.util.Set<Long> scope) {
        int window = SelectionDailyService.COMPLETE_LOOKBACK_DAYS;
        long catchingUp = earnings.incompleteFinancials(d.minusDays(window), d).stream()
                .filter(f -> scope.contains(f.instrumentId())).count();
        List<SelectionEarningsRepository.IncompleteFinancials> overdue = earnings
                .incompleteFinancials(d.minusDays(OVERDUE_WINDOW_DAYS), d.minusDays(window + 1L)).stream()
                .filter(f -> scope.contains(f.instrumentId())).toList();
        List<String> samples = overdue.stream()
                .map(f -> f.symbol() + " " + f.pubDate() + " 公告，缺 " + String.join("、", f.missing()))
                .toList();
        String detail = (catchingUp > 0 ? "公告后 " + window + " 天内报表未到齐、每日续取中 " + catchingUp + " 只；" : "公告后 " + window + " 天内的报表都已到齐；")
                + (overdue.isEmpty() ? "没有超期仍缺的"
                : "超过 " + window + " 天仍缺 " + overdue.size() + " 只（富途一直没给，已不再续取，评分用库里已有的季度）");
        return new BarAuditService.Check("financialsComplete", overdue.isEmpty(), false, detail, overdue.size(), samples);
    }

    /** 默认审计日 = 最近一个已收盘落定的交易日（模拟交易重算的缺省截止日也用它）。 */
    public LocalDate cutoff() {
        return settledCutoff.get();
    }

    public BarAuditService.Report audit(LocalDate date) {
        LocalDate d = date == null ? settledCutoff.get() : date;
        int n = targets.all().size();
        SelectionScoreRepository.DayStats st = scores.dayStats(SelectionModel.VERSION, d);
        long valued = valuations.countOn(d);
        SelectionPriceSeamRepository.Summary seam = seams.summary();
        List<BarAuditService.Check> checks = new ArrayList<>();

        checks.add(new BarAuditService.Check("scored", st.rows() > 0, true,
                st.rows() > 0 ? "已评分 " + st.rows() + " 只（范围 " + n + "）" : d + " 没有评分结果", st.rows(), List.of()));
        boolean ratedOk = n > 0 && st.rated() >= MIN_RATED_SHARE * n;
        checks.add(new BarAuditService.Check("ratedShare", ratedOk, true,
                "可评级 " + st.rated() + "/" + n + "（下限 " + Math.round(MIN_RATED_SHARE * 100) + "%）", st.rated(), List.of()));
        boolean valuedOk = n > 0 && valued >= MIN_VALUATION_SHARE * n;
        checks.add(new BarAuditService.Check("valuationFresh", valuedOk, true,
                "当天有估值 " + valued + "/" + n + "（下限 " + Math.round(MIN_VALUATION_SHARE * 100) + "%；没有就是估值快照或续接没跑）",
                valued, List.of()));
        checks.add(new BarAuditService.Check("ratingDistribution", true, false, "评级分布 " + st.byRating(), st.rated(), List.of()));
        checks.add(new BarAuditService.Check("exclusions", true, false, "排除原因 " + st.byExclusion(),
                st.byExclusion().values().stream().mapToLong(Long::longValue).sum(), List.of()));
        checks.add(new BarAuditService.Check("ratedBySector", true, false, "各板块评级只数 " + st.ratedBySector(),
                st.ratedBySector().size(), List.of()));
        checks.add(new BarAuditService.Check("priceSeams", true, false,
                "盈透接缝 " + seam.ok() + "/" + seam.checked() + " 通过（不通过的在需要盈透段的日子动能记缺）",
                seam.checked() - seam.ok(), List.of()));
        checks.add(financialsComplete(d, targets.all().stream().map(org.jdkxx.trader.storage.marketdata.InstrumentRow::id)
                .collect(java.util.stream.Collectors.toSet())));
        checks.addAll(sim.auditChecks(d));
        checks.add(new BarAuditService.Check("knownBiases", true, false, "已知偏差（回测报告必须原样带出）", BIASES.size(), BIASES));

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("model", SelectionModel.VERSION);
        summary.put("targets", n);
        summary.put("scored", st.rows());
        summary.put("rated", st.rated());
        boolean ok = checks.stream().filter(BarAuditService.Check::critical).allMatch(BarAuditService.Check::ok);
        return new BarAuditService.Report(d, ok, Instant.now(clock), summary, checks);
    }
}
