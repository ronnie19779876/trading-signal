package org.jdkxx.trader.app.web;

import org.jdkxx.trader.core.account.AccountAuditService;
import org.jdkxx.trader.core.account.AccountFacade;
import org.jdkxx.trader.core.account.HoldingReviewService;
import org.jdkxx.trader.core.account.HoldingSyncService;
import org.jdkxx.trader.core.account.LiveAccountService;
import org.jdkxx.trader.core.marketdata.audit.BarAuditService;
import org.jdkxx.trader.storage.account.AccountSnapshotRow;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 账户与持仓（第 3 期）：快照作业、快照查询、持仓同步、账户审计；3.0.2 起加实时账户；3.5.0 起加持仓风险面板。账户号只以脱敏形式出现。只在存储启用时装配。
 */
@RestController
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class AccountController {

    private final AccountFacade facade;
    private final HoldingSyncService holdingSync;
    private final AccountAuditService audit;
    private final LiveAccountService live;
    private final HoldingReviewService review;

    public AccountController(AccountFacade facade, HoldingSyncService holdingSync, AccountAuditService audit,
                             LiveAccountService live, HoldingReviewService review) {
        this.facade = facade;
        this.holdingSync = holdingSync;
        this.audit = audit;
        this.live = live;
        this.review = review;
    }

    /**
     * 实时账户：资金、盈亏、持仓（盈透常驻订阅，只读内存）。第一次读发起订阅（WARMING），5 分钟没人读自动退订。
     * 各部分自带更新时间：资金约 3 分钟一推，盈亏与逐只市值按变化秒级推送。
     */
    @GetMapping("/api/account/live")
    public LiveAccountService.LiveView live() {
        return live.view();
    }

    /** 账户快照作业。只能在快照窗口内拍（窗口外 409）；force=true 只在开发环境可用。 */
    @PostMapping("/api/account/snapshot")
    public Map<String, Object> snapshot(@RequestParam(defaultValue = "false") boolean force) {
        return Map.of("jobId", facade.snapshot("MANUAL", force));
    }

    /** 最新一份快照，附与上一份快照相比的日变化。 */
    @GetMapping("/api/account/snapshots/latest")
    public AccountFacade.SnapshotView latest() {
        return facade.latest().orElseThrow(() -> new NoSuchElementException("还没有账户快照"));
    }

    @GetMapping("/api/account/snapshots")
    public List<AccountSnapshotRow> snapshots(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate start = from == null ? end.minusDays(90) : from;
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("from 不能晚于 to");
        }
        return facade.between(start, end);
    }

    /** 按盈透持仓维护池里的 HOLDING。默认只返回计划；apply=true 才改池。 */
    @PostMapping("/api/account/holdings/sync")
    public HoldingSyncService.Result syncHoldings(@RequestParam(defaultValue = "false") boolean apply) throws Exception {
        return holdingSync.syncNow(apply, "MANUAL");
    }

    /**
     * 持仓风险面板（3.5.0，ARCHITECTURE §29）：每只持仓（与池里没持有的）的趋势状态、财报预警、历史财报反应与压力情景、触发的复核条件。
     * 只读，只查库。只检查用户定的规则，不给买卖建议。
     */
    @GetMapping("/api/account/holdings-review")
    public HoldingReviewService.Review holdingsReview(@RequestParam(required = false) Integer days) {
        return review.review(days);
    }

    /** 加仓检查：按用户定的四条规则逐条判定「准备再买 shares 股」，只读。没有这个标的 404。 */
    @GetMapping("/api/account/add-check/{symbol}")
    public HoldingReviewService.AddCheck addCheck(@PathVariable String symbol, @RequestParam double shares) {
        return review.addCheck(symbol, shares);
    }

    /** 逐日的趋势状态与复核条件（历史验证用，scripts/research/holdings_review_report.py），只读。 */
    @GetMapping("/api/account/holdings-review/history/{symbol}")
    public List<HoldingReviewService.HistoryDay> holdingsReviewHistory(
            @PathVariable String symbol,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return review.history(symbol, from, to);
    }

    /** 账户审计（收盘巡检）：默认审最近一个已收盘交易日。 */
    @GetMapping("/api/account/audit")
    public BarAuditService.Report audit(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return audit.audit(date);
    }
}
