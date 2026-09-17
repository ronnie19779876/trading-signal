package org.jdkxx.trader.core.signal.ai;

import org.jdkxx.trader.core.marketdata.InstrumentDirectory;
import org.jdkxx.trader.core.signal.SentinelService;
import org.jdkxx.trader.domain.signal.SentinelEvaluation;
import org.jdkxx.trader.storage.signal.AiAnalysisRepository;
import org.jdkxx.trader.storage.signal.AiAnalysisRow;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/** 模型分析的查询与手工调用。手工调用同样计入每日上限、同样复用同一输入的已有结论。 */
public class AiAnalysisFacade {

    private final AiVetoService ai;
    private final SentinelService sentinel;
    private final InstrumentDirectory directory;
    private final AiAnalysisRepository analyses;
    private final Clock clock;
    private final ZoneId zone;

    public AiAnalysisFacade(AiVetoService ai, SentinelService sentinel, InstrumentDirectory directory, AiAnalysisRepository analyses,
                            Clock clock, ZoneId zone) {
        this.ai = ai;
        this.sentinel = sentinel;
        this.directory = directory;
        this.analyses = analyses;
        this.clock = clock;
        this.zone = zone;
    }

    /** 同步调用（约 15 秒）。当天不予判定 409；返回落库的那一行（可能是复用或因预算跳过）。 */
    public AiAnalysisRow analyze(String symbol, LocalDate date) {
        SentinelService.Judgement j = sentinel.evaluate(symbol, date);
        if (j.evaluation().status() != SentinelEvaluation.Status.EVALUATED) {
            throw new IllegalStateException(j.symbol() + " " + j.evaluation().asOf() + " 不予判定：" + j.evaluation().statusDetail());
        }
        AiVetoService.Outcome o = ai.analyze(directory.require(symbol), j.evaluation(), "MANUAL", null, null);
        return analyses.find(o.analysisId()).orElseThrow();
    }

    public AiAnalysisRow find(long id) {
        return analyses.find(id).orElseThrow(() -> new NoSuchElementException("分析 #" + id + " 不存在"));
    }

    /** 按创建日（美东）过滤，默认最近 7 天。 */
    public List<AiAnalysisRow> list(LocalDate from, LocalDate to, String status, String symbol, int limit) {
        LocalDate end = to == null ? LocalDate.now(clock.withZone(zone)) : to;
        LocalDate start = from == null ? end.minusDays(7) : from;
        return analyses.list(start.atStartOfDay(zone).toInstant(), end.plusDays(1).atStartOfDay(zone).toInstant(), status,
                symbol == null ? null : directory.require(symbol).symbol(), limit);
    }

    public record Usage(Map<String, Object> settings, List<AiAnalysisRepository.DailyUsage> days) {
    }

    /** 按美东自然日汇总，默认最近 30 天。 */
    public Usage usage(LocalDate from, LocalDate to) {
        LocalDate end = to == null ? LocalDate.now(clock.withZone(zone)) : to;
        LocalDate start = from == null ? end.minusDays(30) : from;
        Instant a = start.atStartOfDay(zone).toInstant();
        Instant b = end.plusDays(1).atStartOfDay(zone).toInstant();
        return new Usage(ai.settings(), analyses.usage(a, b, zone.getId()));
    }
}
