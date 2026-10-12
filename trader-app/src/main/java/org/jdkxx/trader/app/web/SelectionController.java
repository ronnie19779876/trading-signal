package org.jdkxx.trader.app.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdkxx.trader.core.marketdata.audit.BarAuditService;
import org.jdkxx.trader.core.selection.BackfillPart;
import org.jdkxx.trader.core.selection.SelectionFacade;
import org.jdkxx.trader.core.selection.SelectionInputsService;
import org.jdkxx.trader.core.selection.SelectionSimService;
import org.jdkxx.trader.storage.selection.SelectionPriceSeamRepository;
import org.jdkxx.trader.storage.selection.SelectionScoreRepository;
import org.jdkxx.trader.storage.selection.SelectionSimRepository;
import org.jdkxx.trader.storage.selection.SimTradeRow;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 量化选股（第 6 期，ARCHITECTURE §25）。独立功能，与入场信号的接口互不相干。只在存储启用时装配。
 */
@RestController
@RequestMapping("/api/selection")
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class SelectionController {

    private final SelectionFacade facade;
    private final ObjectMapper json;

    public SelectionController(SelectionFacade facade, ObjectMapper json) {
        this.facade = facade;
        this.json = json;
    }

    /**
     * 输入回补（异步作业）。part 取 prices / valuation / earnings / financials；
     * symbols 逗号分隔、缺省为全部范围；已完成的跳过，force=true 重取。
     */
    @PostMapping("/backfill")
    public Map<String, Object> backfill(@RequestParam String part, @RequestParam(required = false) String symbols,
                                        @RequestParam(defaultValue = "false") boolean force) {
        BackfillPart p = part(part);
        List<String> list = symbols == null || symbols.isBlank() ? List.of()
                : Arrays.stream(symbols.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        return Map.of("jobId", facade.backfill("MANUAL", p, list, force));
    }

    /** 输入覆盖报告（6.1 验收用）：各部分状态、失败与空回清单、接缝、估值与快照一致性、公告日匹配、季报深度。 */
    @GetMapping("/inputs")
    public SelectionInputsService.Report inputs() {
        return facade.inputs();
    }

    /** 盈透与富途的接缝核对明细；failedOnly=true 只看不通过的。 */
    @GetMapping("/seams")
    public List<SelectionPriceSeamRepository.SeamRow> seams(@RequestParam(defaultValue = "false") boolean failedOnly) {
        return facade.seams(failedOnly);
    }

    /** 某天全表，按合成分从高到低；date 缺省取最近一个评分日。rating 取 strong_buy / buy / hold / sell / strong_sell。 */
    @GetMapping("/scores")
    public List<SelectionScoreRepository.ScoreView> scores(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String sector, @RequestParam(required = false) String rating) {
        return facade.day(date, sector, rating);
    }

    /** 最近的评分日（从新到旧），默认 60 个。 */
    @GetMapping("/days")
    public List<LocalDate> days(@RequestParam(defaultValue = "60") int limit) {
        return facade.days(limit);
    }

    /** 单只历史；默认最近一年。 */
    @GetMapping("/scores/{symbol}")
    public List<SelectionScoreRepository.ScoreView> history(@PathVariable String symbol,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate start = from == null ? end.minusYears(1) : from;
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("from 不能晚于 to");
        }
        return facade.history(symbol, start, end);
    }

    /** 量化选股审计：默认审最近一个已收盘落定的交易日。 */
    @GetMapping("/audit")
    public BarAuditService.Report audit(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return facade.audit(date);
    }

    /** 重算某一天（异步，只打分）。 */
    @PostMapping("/score")
    public Map<String, Object> score(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return Map.of("jobId", facade.score("MANUAL", date));
    }

    /** 历史重放（异步）：逐个交易日打分并落库，sb_streak 按日期顺序接续。 */
    @PostMapping("/replay")
    public Map<String, Object> replay(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return Map.of("jobId", facade.replay("MANUAL", from, to));
    }

    /** 每日作业（异步）：估值续接 → 新公告补取 → 打分。手工补跑用。 */
    @PostMapping("/daily")
    public Map<String, Object> daily(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return Map.of("jobId", facade.daily("MANUAL", date));
    }

    // ---------------------------------------------------------------- 模拟交易（§26）

    /** 模拟账户概况：规则、现金、持仓、已实现与浮动盈亏、SPY 同期、最新评分日前 N 名能否加入。 */
    @GetMapping("/sim")
    public SelectionSimService.Overview simOverview() {
        return facade.simOverview();
    }

    /** 加入前预览：止损（及其来源）、压力区、最早入场日、估算股数。不写库。 */
    @GetMapping("/sim/preview")
    public SelectionSimService.Preview simPreview(@RequestParam String symbol) {
        return facade.simPreview(symbol);
    }

    /** 加入模拟池：只接受最新评分日合成分前 N 名；点击之后的第一个开盘价入场。 */
    @PostMapping("/sim/trades")
    public SimTradeRow simAdd(@RequestParam String symbol) {
        return facade.simAdd(symbol);
    }

    /** 平仓：持有中的点击之后第一个开盘价成交；还没入场的直接撤销。 */
    @PostMapping("/sim/trades/{id}/close")
    public SimTradeRow simClose(@PathVariable long id, @RequestParam(required = false) String note) {
        return facade.simClose(id, note);
    }

    /** 逐笔，按加入时间倒序；status 取 pending_entry / open / closed / cancelled，缺省全部。 */
    @GetMapping("/sim/trades")
    public List<SimTradeRow> simTrades(@RequestParam(required = false) String status) {
        return facade.simTrades(status);
    }

    @GetMapping("/sim/trades/{id}")
    public SelectionSimService.TradeDetail simTrade(@PathVariable long id) {
        return facade.simTrade(id);
    }

    /** 操作留痕（加入、成交、止盈提示、平仓请求、平仓、周报），新的在前。 */
    @GetMapping("/sim/events")
    public List<SelectionSimRepository.Event> simEvents(@RequestParam(defaultValue = "100") int limit) {
        return facade.simEvents(limit);
    }

    /** 逐日权益（收盘后）。默认最近一年。 */
    @GetMapping("/sim/equity")
    public List<SelectionSimRepository.EquityRow> simEquity(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate end = to == null ? LocalDate.now() : to;
        return facade.simEquity(from == null ? end.minusYears(1) : from, end);
    }

    /** 周报列表（新的在前，不带正文）。 */
    @GetMapping("/sim/weekly")
    public List<WeeklyView> simWeeklies(@RequestParam(defaultValue = "52") int limit) {
        return facade.simWeeklies(limit).stream().map(w -> view(w, false)).toList();
    }

    /** 某周周报（weekEnd = 该周最后一个交易日），带正文。 */
    @GetMapping("/sim/weekly/{weekEnd}")
    public WeeklyView simWeekly(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekEnd) {
        return view(facade.simWeeklyRow(weekEnd), true);
    }

    /** 生成周报（异步）：weekEnd 缺省 = 最近一个已收盘落定的交易日所在周；已有的不覆盖，force=true 覆盖并留痕。 */
    @PostMapping("/sim/weekly")
    public Map<String, Object> simWeeklyGenerate(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekEnd,
                                                 @RequestParam(defaultValue = "false") boolean force) {
        return Map.of("jobId", facade.simWeekly("MANUAL", weekEnd, force));
    }

    /** 重算到 through（异步，缺省 = 最近一个已收盘落定的交易日）；每日作业里也会跑一次。 */
    @PostMapping("/sim/update")
    public Map<String, Object> simUpdate(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate through) {
        return Map.of("jobId", facade.simUpdate("MANUAL", through));
    }

    /** 周报：正文是落库的 jsonb，原样转成 JSON 对象返回（不是字符串）。 */
    public record WeeklyView(LocalDate weekEnd, LocalDate weekStart, String rulesVersion, java.math.BigDecimal equityEnd,
                             java.math.BigDecimal weekReturn, java.math.BigDecimal spyWeekReturn, int closedTrades,
                             java.time.Instant generatedAt, JsonNode report) {
    }

    private WeeklyView view(SelectionSimRepository.WeeklyRow w, boolean withReport) {
        try {
            return new WeeklyView(w.weekEnd(), w.weekStart(), w.rulesVersion(), w.equityEnd(), w.weekReturn(), w.spyWeekReturn(),
                    w.closedTrades(), w.generatedAt(), withReport ? json.readTree(w.report()) : null);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("周报正文解析失败：" + e.getOriginalMessage());
        }
    }

    private static BackfillPart part(String raw) {
        try {
            return BackfillPart.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("part 应为 prices / valuation / earnings / financials / types，收到：" + raw);
        }
    }
}
