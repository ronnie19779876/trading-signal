package org.jdkxx.trader.app.web;

import org.jdkxx.trader.core.marketdata.audit.BarAuditService;
import org.jdkxx.trader.core.signal.SentinelService;
import org.jdkxx.trader.core.signal.SignalAuditService;
import org.jdkxx.trader.core.signal.SignalFacade;
import org.jdkxx.trader.core.signal.ai.SignalPayloadBuilder;
import org.jdkxx.trader.storage.signal.EntrySignalRow;
import org.jdkxx.trader.storage.signal.SignalEvaluationRow;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 入场信号（第 4 期）：评估作业、信号、每日评估、纸面账本、审计，以及只读的单日判定与区间回放。只在存储启用时装配。
 */
@RestController
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class SignalController {

    private final SentinelService sentinel;
    private final SignalFacade facade;
    private final SignalAuditService audit;
    private final SignalPayloadBuilder payloads;

    public SignalController(SentinelService sentinel, SignalFacade facade, SignalAuditService audit, SignalPayloadBuilder payloads) {
        this.sentinel = sentinel;
        this.facade = facade;
        this.audit = audit;
        this.payloads = payloads;
    }

    /** 预览发给模型的输入（不调模型、不计费）：某只某天的判定 + 技术面 + 估值 + 财报 + 公司简介。 */
    @GetMapping("/api/signals/ai-input/{symbol}")
    public Map<String, Object> aiInput(@PathVariable String symbol,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        SentinelService.Judgement j = sentinel.evaluate(symbol, date);
        if (j.evaluation().status() != org.jdkxx.trader.domain.signal.SentinelEvaluation.Status.EVALUATED) {
            throw new IllegalStateException(j.symbol() + " " + j.evaluation().asOf() + " 不予判定：" + j.evaluation().statusDetail());
        }
        return payloads.build(facade.instrument(symbol), j.evaluation(), LocalDate.now());
    }

    /** 某只某天的四门判定过程（现场计算）。date 缺省取最近收盘落定的交易日。 */
    @GetMapping("/api/signals/evaluate/{symbol}")
    public SentinelService.Judgement evaluate(@PathVariable String symbol,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return sentinel.evaluate(symbol, date);
    }

    /**
     * 区间回放：逐个交易日判定，按边沿与冷却标出信号（历史上没有 AI 结论）。默认最近一年，最长 21 年。
     * trades=true 附每条信号的纸面交易；stopAtr / half 只改纸面交易的出场（同一批信号配对比较用），不改判定。
     */
    @GetMapping("/api/signals/replay/{symbol}")
    public SentinelService.Replay replay(@PathVariable String symbol,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                         @RequestParam(defaultValue = "false") boolean trades,
                                         @RequestParam(required = false) Double stopAtr,
                                         @RequestParam(defaultValue = "false") boolean half) {
        return sentinel.replay(symbol, from, to, trades, stopAtr, half);
    }

    /** 画图用的 K 线：价格尺度折回 asOf 那天，与当天的信号价位对齐。默认 asOf = to = 收盘落定日、from 往前一年，最长 3 年。 */
    @GetMapping("/api/signals/bars/{symbol}")
    public List<SentinelService.ChartBar> chartBars(@PathVariable String symbol,
                                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
                                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return sentinel.chartBars(symbol, asOf, from, to);
    }

    /** 提交评估作业（写库）。date 缺省取收盘落定日；过去的日期记为补跑（BACKFILL）。 */
    @PostMapping("/api/signals/evaluate")
    public Map<String, Long> submit(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return Map.of("jobId", facade.evaluate("MANUAL", date));
    }

    /** 信号列表（默认最近 30 天、只看池与持仓），附 BASE 变体的账本状态。status 逗号分隔。 */
    @GetMapping("/api/signals")
    public List<SignalFacade.SignalView> list(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                              @RequestParam(required = false) String status,
                                              @RequestParam(defaultValue = "pool") String scope,
                                              @RequestParam(required = false) String origin) {
        Set<String> statuses = status == null || status.isBlank() ? Set.of()
                : Arrays.stream(status.split(",")).map(x -> x.trim().toUpperCase(Locale.ROOT)).collect(Collectors.toSet());
        return facade.list(from, to, statuses, poolOnly(scope), origin == null ? null : origin.toUpperCase(Locale.ROOT));
    }

    @GetMapping("/api/signals/{id}")
    public SignalFacade.SignalDetail detail(@PathVariable long id) {
        return facade.detail(id);
    }

    public record StatusChange(String status, String note) {
    }

    /** 标记已看过（ACKNOWLEDGED）或不做（DISMISSED）。 */
    @PostMapping("/api/signals/{id}/status")
    public EntrySignalRow changeStatus(@PathVariable long id, @RequestBody StatusChange body) {
        if (body == null || body.status() == null) {
            throw new IllegalArgumentException("需要 status");
        }
        return facade.changeStatus(id, body.status().toUpperCase(Locale.ROOT), body.note());
    }

    /** 某天的全部评估：回答"今天为什么没信号、卡在哪道门"。outcome 也接受 SKIPPED_* 状态名。 */
    @GetMapping("/api/signals/evaluations")
    public List<SignalEvaluationRow> evaluations(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                                 @RequestParam(required = false) String outcome,
                                                 @RequestParam(required = false) String gate,
                                                 @RequestParam(defaultValue = "all") String scope) {
        return facade.evaluationsOn(date, upper(outcome), upper(gate), poolOnly(scope));
    }

    /** 单只的评估历史，默认最近 90 天。 */
    @GetMapping("/api/signals/evaluations/{symbol}")
    public List<SignalEvaluationRow> history(@PathVariable String symbol,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return facade.history(symbol, from, to);
    }

    /** 纸面账本：汇总（按变体与来源）+ 明细，variant / status 过滤明细。 */
    @GetMapping("/api/signals/ledger")
    public SignalFacade.Ledger ledger(@RequestParam(required = false) String variant, @RequestParam(required = false) String status) {
        return facade.ledger(upper(variant), upper(status));
    }

    /** 信号审计（收盘巡检第五段）。 */
    @GetMapping("/api/signals/audit")
    public BarAuditService.Report audit(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return audit.audit(date);
    }

    private static boolean poolOnly(String scope) {
        return switch (scope.toLowerCase(Locale.ROOT)) {
            case "pool" -> true;
            case "all" -> false;
            default -> throw new IllegalArgumentException("scope 只能是 pool 或 all");
        };
    }

    private static String upper(String s) {
        return s == null || s.isBlank() ? null : s.trim().toUpperCase(Locale.ROOT);
    }
}
