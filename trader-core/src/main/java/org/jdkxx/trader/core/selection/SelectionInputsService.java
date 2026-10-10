package org.jdkxx.trader.core.selection;

import org.jdkxx.trader.storage.selection.SelectionBackfillStateRepository;
import org.jdkxx.trader.storage.selection.SelectionCoverageRepository;
import org.jdkxx.trader.storage.selection.SelectionEarningsRepository;
import org.jdkxx.trader.storage.selection.SelectionPriceBarRepository;
import org.jdkxx.trader.storage.selection.SelectionPriceSeamRepository;
import org.jdkxx.trader.storage.selection.SelectionValuationRepository;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 量化选股输入的覆盖报告（§25.9 的 6.1 验收）：只读，不改任何数据。
 * 每一项都把分母与样本摆出来，不只给一个「通过 / 不通过」。
 */
public class SelectionInputsService {

    /** 公告日核对的起点：富途只给最近 20 季，再往前本来就没有。 */
    static final int EARNINGS_LOOKBACK_YEARS = 4;
    static final int SAMPLES = 30;

    /**
     * @param missing 范围内还没有任何状态的只数（没取过）
     */
    public record PartStatus(Map<String, Long> byStatus, long missing) {
    }

    public record Report(LocalDate asOf, int targets, Map<BackfillPart, PartStatus> parts,
                         List<SelectionBackfillStateRepository.StateRow> failed,
                         List<SelectionBackfillStateRepository.StateRow> empty,
                         SelectionPriceBarRepository.Coverage priceBars,
                         SelectionPriceSeamRepository.Summary seams,
                         SelectionValuationRepository.Coverage valuation,
                         SelectionValuationRepository.Agreement valuationVsSnapshot,
                         SelectionEarningsRepository.Matching earnings,
                         SelectionCoverageRepository.FinancialDepth financials) {
    }

    private final SelectionProperties props;
    private final SelectionTargets targets;
    private final SelectionBackfillStateRepository states;
    private final SelectionPriceBarRepository priceBars;
    private final SelectionPriceSeamRepository seams;
    private final SelectionValuationRepository valuations;
    private final SelectionEarningsRepository earnings;
    private final SelectionCoverageRepository coverage;
    private final Clock clock;
    private final ZoneId zone;

    public SelectionInputsService(SelectionProperties props, SelectionTargets targets, SelectionBackfillStateRepository states,
                                  SelectionPriceBarRepository priceBars, SelectionPriceSeamRepository seams,
                                  SelectionValuationRepository valuations, SelectionEarningsRepository earnings,
                                  SelectionCoverageRepository coverage, Clock clock, ZoneId zone) {
        this.props = props;
        this.targets = targets;
        this.states = states;
        this.priceBars = priceBars;
        this.seams = seams;
        this.valuations = valuations;
        this.earnings = earnings;
        this.coverage = coverage;
        this.clock = clock;
        this.zone = zone;
    }

    public Report report() {
        LocalDate today = LocalDate.now(clock.withZone(zone));
        int n = targets.all().size();
        Map<BackfillPart, Map<String, Long>> grouped = new EnumMap<>(BackfillPart.class);
        for (BackfillPart p : BackfillPart.values()) {
            grouped.put(p, new LinkedHashMap<>());
        }
        for (SelectionBackfillStateRepository.Count c : states.counts()) {
            grouped.get(BackfillPart.valueOf(c.part())).put(c.status(), c.n());
        }
        Map<BackfillPart, PartStatus> parts = new EnumMap<>(BackfillPart.class);
        grouped.forEach((p, m) -> parts.put(p, new PartStatus(m, Math.max(0, n - m.values().stream().mapToLong(Long::longValue).sum()))));
        // 季报要覆盖 2016 年一季度：2018 年起回测时 TTM 同比要往前 8 季
        LocalDate needBy = LocalDate.of(props.priceFrom().getYear(), 3, 31);
        return new Report(today, n, parts, states.findByStatus("FAILED", SAMPLES * 2), states.findByStatus("EMPTY", SAMPLES * 2),
                priceBars.coverage(), seams.summary(), valuations.coverage(), valuations.snapshotAgreement(SAMPLES),
                earnings.matching(today.minusYears(EARNINGS_LOOKBACK_YEARS), SAMPLES), coverage.financialDepth(needBy, SAMPLES));
    }
}
