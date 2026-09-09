package org.jdkxx.trader.app.web;

import org.jdkxx.trader.core.marketdata.audit.BarAuditService;
import org.jdkxx.trader.core.marketdata.fundamentals.FundamentalsAuditService;
import org.jdkxx.trader.core.marketdata.fundamentals.FundamentalsFacade;
import org.jdkxx.trader.core.marketdata.fundamentals.FundamentalsQueryService;
import org.jdkxx.trader.domain.FinancialReport;
import org.jdkxx.trader.domain.FinancialStatement;
import org.jdkxx.trader.domain.ValuationSnapshot;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 基本面：估值快照、财务报表、公司简介、覆盖与审计。只在存储启用时装配。
 */
@RestController
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class FundamentalsController {

    private final FundamentalsFacade facade;
    private final FundamentalsAuditService audit;

    public FundamentalsController(FundamentalsFacade facade, FundamentalsAuditService audit) {
        this.facade = facade;
        this.audit = audit;
    }

    /** 一只标的的基本面概览：最新估值 + 最近几期主要指标 + 公司简介。 */
    @GetMapping("/api/fundamentals/{symbol}")
    public FundamentalsQueryService.Overview overview(@PathVariable String symbol) {
        return facade.query().overview(symbol);
    }

    /** 估值时间序列。默认最近 90 天。 */
    @GetMapping("/api/fundamentals/{symbol}/valuation")
    public List<ValuationSnapshot> valuation(@PathVariable String symbol,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate start = from == null ? end.minusDays(90) : from;
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("from 不能晚于 to");
        }
        return facade.query().valuations(symbol, start, end);
    }

    /** 财报期次与数据项。statement 取 income / balance_sheet / cash_flow / main_index。 */
    @GetMapping("/api/fundamentals/{symbol}/reports")
    public List<FinancialReport> reports(@PathVariable String symbol,
                                         @RequestParam(defaultValue = "main_index") String statement,
                                         @RequestParam(defaultValue = "8") int limit) {
        return facade.query().reports(symbol, statement(statement), limit);
    }

    @GetMapping("/api/fundamentals/coverage")
    public FundamentalsQueryService.Coverage coverage() {
        return facade.query().coverage();
    }

    /** 基本面审计：默认审最近一个应有收盘数据的交易日。 */
    @GetMapping("/api/fundamentals/audit")
    public BarAuditService.Report audit(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return audit.audit(date);
    }

    @PostMapping("/api/fundamentals/valuation/refresh")
    public Map<String, Object> refreshValuation() {
        return Map.of("jobId", facade.refreshValuation("MANUAL"));
    }

    /** all=false 只做池与持仓（约 80 秒）；all=true 做全量成分股（518 只约 41 分钟，不取公司简介）。 */
    @PostMapping("/api/fundamentals/financials/refresh")
    public Map<String, Object> refreshFinancials(@RequestParam(defaultValue = "false") boolean all) {
        return Map.of("jobId", facade.refreshFinancials("MANUAL", all));
    }

    private static FinancialStatement statement(String raw) {
        try {
            return FinancialStatement.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("statement 只能是 income / balance_sheet / cash_flow / main_index，收到：" + raw);
        }
    }
}
