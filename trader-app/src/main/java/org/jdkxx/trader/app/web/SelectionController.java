package org.jdkxx.trader.app.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

/**
 * Quantitative stock selection (phase 6, ARCHITECTURE §25). A separate feature, unrelated to the
 * entry-signal endpoints. Only wired when storage is enabled.
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
   * Input backfill (async job). part is prices / valuation / earnings / financials; symbols is
   * comma-separated and defaults to the whole universe; finished ones are skipped, force=true
   * refetches.
   */
  @PostMapping("/backfill")
  public Map<String, Object> backfill(
      @RequestParam String part,
      @RequestParam(required = false) String symbols,
      @RequestParam(defaultValue = "false") boolean force) {
    BackfillPart p = part(part);
    List<String> list =
        symbols == null || symbols.isBlank()
            ? List.of()
            : Arrays.stream(symbols.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    return Map.of("jobId", facade.backfill("MANUAL", p, list, force));
  }

  /**
   * Input coverage report (for 6.1 acceptance): status of each part, failed and empty lists, seams,
   * valuation vs snapshot consistency, announcement-date matching, quarterly report depth.
   */
  @GetMapping("/inputs")
  public SelectionInputsService.Report inputs() {
    return facade.inputs();
  }

  /** IBKR vs Futu seam-check details; failedOnly=true shows only the failures. */
  @GetMapping("/seams")
  public List<SelectionPriceSeamRepository.SeamRow> seams(
      @RequestParam(defaultValue = "false") boolean failedOnly) {
    return facade.seams(failedOnly);
  }

  /**
   * The full table of one day, by composite score descending; date defaults to the latest scoring
   * day. rating is strong_buy / buy / hold / sell / strong_sell.
   */
  @GetMapping("/scores")
  public List<SelectionScoreRepository.ScoreView> scores(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      @RequestParam(required = false) String sector,
      @RequestParam(required = false) String rating) {
    return facade.day(date, sector, rating);
  }

  /** Recent scoring days (newest first), 60 by default. */
  @GetMapping("/days")
  public List<LocalDate> days(@RequestParam(defaultValue = "60") int limit) {
    return facade.days(limit);
  }

  /** One symbol's history; the last year by default. */
  @GetMapping("/scores/{symbol}")
  public List<SelectionScoreRepository.ScoreView> history(
      @PathVariable String symbol,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    LocalDate end = to == null ? LocalDate.now() : to;
    LocalDate start = from == null ? end.minusYears(1) : from;
    if (start.isAfter(end)) {
      throw new IllegalArgumentException("from 不能晚于 to");
    }
    return facade.history(symbol, start, end);
  }

  /** Selection audit: audits the latest settled trading day by default. */
  @GetMapping("/audit")
  public BarAuditService.Report audit(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate date) {
    return facade.audit(date);
  }

  /** Recomputes one day (async, scoring only). */
  @PostMapping("/score")
  public Map<String, Object> score(
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
    return Map.of("jobId", facade.score("MANUAL", date));
  }

  /**
   * Historical replay (async): scores and stores every trading day; sb_streak carries over in date
   * order.
   */
  @PostMapping("/replay")
  public Map<String, Object> replay(
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return Map.of("jobId", facade.replay("MANUAL", from, to));
  }

  /**
   * Daily job (async): extend valuations, then fetch new announcements, then score. For manual
   * re-runs.
   */
  @PostMapping("/daily")
  public Map<String, Object> daily(
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
    return Map.of("jobId", facade.daily("MANUAL", date));
  }

  // ---------------------------------------------------------------- simulated trading (§26)

  /**
   * Simulated account overview: rules, cash, positions, realized and unrealized P&amp;L, SPY over
   * the same period, and whether the top N of the latest scoring day can be added.
   */
  @GetMapping("/sim")
  public SelectionSimService.Overview simOverview() {
    return facade.simOverview();
  }

  /**
   * Preview before adding: stop (and its source), resistance zone, earliest entry day, estimated
   * shares. No writes.
   */
  @GetMapping("/sim/preview")
  public SelectionSimService.Preview simPreview(@RequestParam String symbol) {
    return facade.simPreview(symbol);
  }

  /**
   * Adds to the simulated pool: only the top N by composite score on the latest scoring day; enters
   * at the first open after the click.
   */
  @PostMapping("/sim/trades")
  public SimTradeRow simAdd(@RequestParam String symbol) {
    return facade.simAdd(symbol);
  }

  /**
   * Close: an open trade fills at the first open after the click; one not yet entered is cancelled
   * outright.
   */
  @PostMapping("/sim/trades/{id}/close")
  public SimTradeRow simClose(@PathVariable long id, @RequestParam(required = false) String note) {
    return facade.simClose(id, note);
  }

  /**
   * Individual trades, newest added first; status is pending_entry / open / closed / cancelled, all
   * by default.
   */
  @GetMapping("/sim/trades")
  public List<SimTradeRow> simTrades(@RequestParam(required = false) String status) {
    return facade.simTrades(status);
  }

  @GetMapping("/sim/trades/{id}")
  public SelectionSimService.TradeDetail simTrade(@PathVariable long id) {
    return facade.simTrade(id);
  }

  /**
   * Audit trail (add, fill, take-profit hint, close request, close, weekly report), newest first.
   */
  @GetMapping("/sim/events")
  public List<SelectionSimRepository.Event> simEvents(
      @RequestParam(defaultValue = "100") int limit) {
    return facade.simEvents(limit);
  }

  /** Daily equity (after the close). The last year by default. */
  @GetMapping("/sim/equity")
  public List<SelectionSimRepository.EquityRow> simEquity(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    LocalDate end = to == null ? LocalDate.now() : to;
    return facade.simEquity(from == null ? end.minusYears(1) : from, end);
  }

  /** Weekly reports (newest first, without the body). */
  @GetMapping("/sim/weekly")
  public List<WeeklyView> simWeeklies(@RequestParam(defaultValue = "52") int limit) {
    return facade.simWeeklies(limit).stream().map(w -> view(w, false)).toList();
  }

  /** One week's report (weekEnd = the last trading day of that week), with the body. */
  @GetMapping("/sim/weekly/{weekEnd}")
  public WeeklyView simWeekly(
      @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekEnd) {
    return view(facade.simWeeklyRow(weekEnd), true);
  }

  /**
   * Generates a weekly report (async): weekEnd defaults to the week of the latest settled trading
   * day; an existing report is not overwritten unless force=true, which overwrites and leaves an
   * audit entry.
   */
  @PostMapping("/sim/weekly")
  public Map<String, Object> simWeeklyGenerate(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate weekEnd,
      @RequestParam(defaultValue = "false") boolean force) {
    return Map.of("jobId", facade.simWeekly("MANUAL", weekEnd, force));
  }

  /**
   * Recomputes up to {@code through} (async, default = the latest settled trading day); the daily
   * job also runs it once.
   */
  @PostMapping("/sim/update")
  public Map<String, Object> simUpdate(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate through) {
    return Map.of("jobId", facade.simUpdate("MANUAL", through));
  }

  /**
   * Weekly report: the body is the stored jsonb, returned as a JSON object as-is (not as a string).
   */
  public record WeeklyView(
      LocalDate weekEnd,
      LocalDate weekStart,
      String rulesVersion,
      java.math.BigDecimal equityEnd,
      java.math.BigDecimal weekReturn,
      java.math.BigDecimal spyWeekReturn,
      int closedTrades,
      java.time.Instant generatedAt,
      JsonNode report) {}

  private WeeklyView view(SelectionSimRepository.WeeklyRow w, boolean withReport) {
    try {
      return new WeeklyView(
          w.weekEnd(),
          w.weekStart(),
          w.rulesVersion(),
          w.equityEnd(),
          w.weekReturn(),
          w.spyWeekReturn(),
          w.closedTrades(),
          w.generatedAt(),
          withReport ? json.readTree(w.report()) : null);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("周报正文解析失败：" + e.getOriginalMessage());
    }
  }

  private static BackfillPart part(String raw) {
    try {
      return BackfillPart.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "part 应为 prices / valuation / earnings / financials / types，收到：" + raw);
    }
  }
}
