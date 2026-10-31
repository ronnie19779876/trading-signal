package org.jdkxx.trader.app.web;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

/**
 * Fundamentals: valuation snapshots, financial statements, company profile, coverage and audit.
 * Only wired when storage is enabled.
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

  /**
   * Market-wide valuation screener: valuations of every instrument on one day (name, industry,
   * index and pool role are merged in by the frontend from /api/universe). date defaults to the
   * latest day with valuations. The literal path takes precedence over the {symbol} mapping below;
   * FundamentalsControllerTest guards this.
   */
  @GetMapping("/api/fundamentals/valuations")
  public FundamentalsQueryService.DayValuations valuationsOn(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate date) {
    return facade.query().valuationsOn(date);
  }

  /**
   * Fundamentals overview of one symbol: latest valuation + key metrics of the last few periods +
   * company profile.
   */
  @GetMapping("/api/fundamentals/{symbol}")
  public FundamentalsQueryService.Overview overview(@PathVariable String symbol) {
    return facade.query().overview(symbol);
  }

  /** Valuation time series. The last 90 days by default. */
  @GetMapping("/api/fundamentals/{symbol}/valuation")
  public List<ValuationSnapshot> valuation(
      @PathVariable String symbol,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    LocalDate end = to == null ? LocalDate.now() : to;
    LocalDate start = from == null ? end.minusDays(90) : from;
    if (start.isAfter(end)) {
      throw new IllegalArgumentException("from 不能晚于 to");
    }
    return facade.query().valuations(symbol, start, end);
  }

  /**
   * Report periods and line items. statement is income / balance_sheet / cash_flow / main_index.
   */
  @GetMapping("/api/fundamentals/{symbol}/reports")
  public List<FinancialReport> reports(
      @PathVariable String symbol,
      @RequestParam(defaultValue = "main_index") String statement,
      @RequestParam(defaultValue = "8") int limit) {
    return facade.query().reports(symbol, statement(statement), limit);
  }

  @GetMapping("/api/fundamentals/coverage")
  public FundamentalsQueryService.Coverage coverage() {
    return facade.query().coverage();
  }

  /** Fundamentals audit: audits the latest trading day that should have closing data by default. */
  @GetMapping("/api/fundamentals/audit")
  public BarAuditService.Report audit(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate date) {
    return audit.audit(date);
  }

  @PostMapping("/api/fundamentals/valuation/refresh")
  public Map<String, Object> refreshValuation() {
    return Map.of("jobId", facade.refreshValuation("MANUAL"));
  }

  /**
   * all=false covers only the pool and holdings (about 80 seconds); all=true covers every index
   * constituent (518 symbols, about 41 minutes, no company profiles).
   */
  @PostMapping("/api/fundamentals/financials/refresh")
  public Map<String, Object> refreshFinancials(@RequestParam(defaultValue = "false") boolean all) {
    return Map.of("jobId", facade.refreshFinancials("MANUAL", all));
  }

  private static FinancialStatement statement(String raw) {
    try {
      return FinancialStatement.valueOf(raw.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "statement 只能是 income / balance_sheet / cash_flow / main_index，收到：" + raw);
    }
  }
}
