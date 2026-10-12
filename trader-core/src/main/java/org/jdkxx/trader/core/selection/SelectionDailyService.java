package org.jdkxx.trader.core.selection;

import org.jdkxx.trader.core.marketdata.jobs.JobContext;
import org.jdkxx.trader.domain.FinancialReport;
import org.jdkxx.trader.domain.FinancialStatement;
import org.jdkxx.trader.domain.selection.ValuationMetric;
import org.jdkxx.trader.domain.selection.ValuationPoint;
import org.jdkxx.trader.domain.selection.ValuationRange;
import org.jdkxx.trader.gateway.NotConnectedException;
import org.jdkxx.trader.gateway.SelectionDataGateway;
import org.jdkxx.trader.storage.marketdata.FinancialRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.jdkxx.trader.storage.selection.SelectionEarningsRepository;
import org.jdkxx.trader.storage.selection.SelectionValuationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * 量化选股的每日与每周维护（§25.7）。
 * <ul>
 *   <li>每日：估值快照续上估值序列 → 近几天发了财报的标的补取最近 4 季季报与近 3 个月估值 → 打分 → 模拟交易重算（§26）；</li>
 *   <li>每周：全部标的刷新公告日，证券类型、估值历史、季报、盈透价格只补缺的（新进成分股由此补齐）。</li>
 * </ul>
 * 补取失败只记部分失败、照样打分（用的是库里已有的数据，审计会显示新鲜度）。
 */
public class SelectionDailyService {

    private static final Logger log = LoggerFactory.getLogger(SelectionDailyService.class);
    /** 公告后几天内补取：覆盖周末与偶发失败 */
    static final int REFRESH_LOOKBACK_DAYS = 6;
    static final int REFRESH_PERIODS = 4;
    private static final List<FinancialStatement> STATEMENTS = List.of(FinancialStatement.INCOME,
            FinancialStatement.BALANCE_SHEET, FinancialStatement.CASH_FLOW);

    private final SelectionTargets targets;
    private final SelectionValuationRepository valuations;
    private final SelectionEarningsRepository earnings;
    private final FinancialRepository financials;
    private final SelectionDataGateway fundamentals;
    private final SelectionScoringService scoring;
    private final SelectionBackfillService backfill;
    private final SelectionSimService sim;

    public SelectionDailyService(SelectionTargets targets, SelectionValuationRepository valuations, SelectionEarningsRepository earnings,
                                 FinancialRepository financials, SelectionDataGateway fundamentals, SelectionScoringService scoring,
                                 SelectionBackfillService backfill, SelectionSimService sim) {
        this.targets = targets;
        this.valuations = valuations;
        this.earnings = earnings;
        this.financials = financials;
        this.fundamentals = fundamentals;
        this.scoring = scoring;
        this.backfill = backfill;
        this.sim = sim;
    }

    public String daily(LocalDate date, JobContext ctx) {
        List<InstrumentRow> all = targets.all();
        ctx.progress("估值快照续接");
        int appended = valuations.appendFromSnapshot(date, all.stream().map(InstrumentRow::id).toList());

        Set<Long> recent = earnings.instrumentsWithPubBetween(date.minusDays(REFRESH_LOOKBACK_DAYS), date);
        List<InstrumentRow> refresh = all.stream().filter(r -> recent.contains(r.id())).toList();
        int ok = 0;
        int failed = 0;
        for (int i = 0; i < refresh.size(); i++) {
            InstrumentRow r = refresh.get(i);
            ctx.progress("补取新公告 " + (i + 1) + "/" + refresh.size() + " " + r.symbol());
            try {
                for (FinancialStatement st : STATEMENTS) {
                    List<FinancialReport> reports = fundamentals.quarterlyFinancials(r.instrument(), st, REFRESH_PERIODS)
                            .get(90, TimeUnit.SECONDS);
                    financials.upsertAll(r.id(), reports);
                }
                for (ValuationMetric m : ValuationMetric.values()) {
                    List<ValuationPoint> points = fundamentals.valuationHistory(r.instrument(), m, ValuationRange.MONTH3)
                            .get(90, TimeUnit.SECONDS);
                    valuations.upsertHistory(r.id(), m, points);
                }
                ok++;
            } catch (Exception e) {
                Throwable c = unwrap(e);
                failed++;
                log.warn("量化选股补取新公告 {} 失败：{}", r.symbol(), c.toString());
                if (c instanceof NotConnectedException) {
                    ctx.partial("网关未连接，新公告补取停在 " + r.symbol());
                    break;
                }
            }
        }
        if (failed > 0) {
            ctx.partial("新公告补取失败 " + failed + " 只");
        }
        String scored = scoring.scoreDay(date, ctx);
        // 模拟交易排在打分之后：只用日 K，与打分结果无关，失败不影响评分（审计的 simUpdated 会提示，22:30 补偿会再跑）
        String simulated;
        try {
            simulated = sim.update(date, ctx);
        } catch (RuntimeException e) {
            log.warn("模拟交易重算失败：{}", e.toString());
            ctx.partial("模拟交易重算失败：" + e.getMessage());
            simulated = "模拟交易重算失败";
        }
        return "估值续接 " + appended + " 只；新公告补取 " + ok + "/" + refresh.size() + "；" + scored + "；" + simulated;
    }

    /**
     * 每周：全部标的刷新公告日（拿到下一季预定日），再对证券类型、估值历史、季报、盈透价格各跑一次<b>只补缺的</b>回补——
     * 已完成的跳过，平时几乎没有请求；成分股同步新进的标的由此补齐深度输入（3.2.0 漏了这条，新成员永远缺 10 年估值与 50 期季报）。
     */
    public String weekly(JobContext ctx) {
        List<String> out = new java.util.ArrayList<>();
        out.add(backfill.run(BackfillPart.EARNINGS, List.of(), true, ctx));
        for (BackfillPart p : WEEKLY_FILL) {
            if (ctx.cancelled()) {
                break;
            }
            out.add(backfill.run(p, List.of(), false, ctx));
        }
        return String.join("；", out);
    }

    /** 周作业只补缺的部分；盈透放最后（节奏最慢）。 */
    static final List<BackfillPart> WEEKLY_FILL = List.of(BackfillPart.TYPES, BackfillPart.VALUATION, BackfillPart.FINANCIALS,
            BackfillPart.PRICES);

    private static Throwable unwrap(Throwable t) {
        Throwable c = t;
        while ((c instanceof ExecutionException || c instanceof CompletionException) && c.getCause() != null) {
            c = c.getCause();
        }
        return c;
    }
}
