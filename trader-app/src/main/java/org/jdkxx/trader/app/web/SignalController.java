package org.jdkxx.trader.app.web;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
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

/**
 * Entry signals (phase 4): evaluation job, signals, daily evaluations, paper ledger, audit, plus
 * read-only single-day judgement and range replay. Only wired when storage is enabled.
 */
@RestController
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class SignalController {

  private final SentinelService sentinel;
  private final SignalFacade facade;
  private final SignalAuditService audit;
  private final SignalPayloadBuilder payloads;

  public SignalController(
      SentinelService sentinel,
      SignalFacade facade,
      SignalAuditService audit,
      SignalPayloadBuilder payloads) {
    this.sentinel = sentinel;
    this.facade = facade;
    this.audit = audit;
    this.payloads = payloads;
  }

  /**
   * Preview of the input sent to the model (does not call the model, not billed): one symbol's
   * judgement on one day + technicals + valuation + financials + company profile.
   */
  @GetMapping("/api/signals/ai-input/{symbol}")
  public Map<String, Object> aiInput(
      @PathVariable String symbol,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate date) {
    SentinelService.Judgement j = sentinel.evaluate(symbol, date);
    if (j.evaluation().status()
        != org.jdkxx.trader.domain.signal.SentinelEvaluation.Status.EVALUATED) {
      throw new IllegalStateException(
          j.symbol() + " " + j.evaluation().asOf() + " 不予判定：" + j.evaluation().statusDetail());
    }
    return payloads.build(facade.instrument(symbol), j.evaluation(), LocalDate.now());
  }

  /**
   * The four-gate judgement for one symbol on one day (computed on the fly). date defaults to the
   * latest settled trading day.
   */
  @GetMapping("/api/signals/evaluate/{symbol}")
  public SentinelService.Judgement evaluate(
      @PathVariable String symbol,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate date) {
    return sentinel.evaluate(symbol, date);
  }

  /**
   * Range replay: judges every trading day and marks signals by edge and cooldown (history has no
   * AI conclusions). The last year by default, at most 21 years. trades=true attaches each signal's
   * paper trade; stopAtr / half only change the paper trade's exit (for paired comparison over the
   * same set of signals), never the judgement.
   */
  @GetMapping("/api/signals/replay/{symbol}")
  public SentinelService.Replay replay(
      @PathVariable String symbol,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(defaultValue = "false") boolean trades,
      @RequestParam(required = false) Double stopAtr,
      @RequestParam(defaultValue = "false") boolean half) {
    return sentinel.replay(symbol, from, to, trades, stopAtr, half);
  }

  /**
   * Bars for charting: the price scale is converted back to the asOf day so it lines up with that
   * day's signal levels. Defaults: asOf = to = the latest settled day, from = one year earlier; at
   * most 3 years.
   */
  @GetMapping("/api/signals/bars/{symbol}")
  public List<SentinelService.ChartBar> chartBars(
      @PathVariable String symbol,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return sentinel.chartBars(symbol, asOf, from, to);
  }

  /**
   * Submits an evaluation job (writes to the database). date defaults to the latest settled day; a
   * past date is recorded as a BACKFILL run.
   */
  @PostMapping("/api/signals/evaluate")
  public Map<String, Long> submit(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate date) {
    return Map.of("jobId", facade.evaluate("MANUAL", date));
  }

  /**
   * Signal list (last 30 days, pool and holdings only by default) with the BASE variant's ledger
   * state. status is comma-separated.
   */
  @GetMapping("/api/signals")
  public List<SignalFacade.SignalView> list(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "pool") String scope,
      @RequestParam(required = false) String origin) {
    Set<String> statuses =
        status == null || status.isBlank()
            ? Set.of()
            : Arrays.stream(status.split(","))
                .map(x -> x.trim().toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
    return facade.list(
        from,
        to,
        statuses,
        poolOnly(scope),
        origin == null ? null : origin.toUpperCase(Locale.ROOT));
  }

  @GetMapping("/api/signals/{id}")
  public SignalFacade.SignalDetail detail(@PathVariable long id) {
    return facade.detail(id);
  }

  public record StatusChange(String status, String note) {}

  /** Marks a signal as seen (ACKNOWLEDGED) or not taken (DISMISSED). */
  @PostMapping("/api/signals/{id}/status")
  public EntrySignalRow changeStatus(@PathVariable long id, @RequestBody StatusChange body) {
    if (body == null || body.status() == null) {
      throw new IllegalArgumentException("需要 status");
    }
    return facade.changeStatus(id, body.status().toUpperCase(Locale.ROOT), body.note());
  }

  /**
   * All evaluations of a day: answers "why no signal today, which gate stopped it". outcome also
   * accepts SKIPPED_* status names.
   */
  @GetMapping("/api/signals/evaluations")
  public List<SignalEvaluationRow> evaluations(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      @RequestParam(required = false) String outcome,
      @RequestParam(required = false) String gate,
      @RequestParam(defaultValue = "all") String scope) {
    return facade.evaluationsOn(date, upper(outcome), upper(gate), poolOnly(scope));
  }

  /** One symbol's evaluation history, the last 90 days by default. */
  @GetMapping("/api/signals/evaluations/{symbol}")
  public List<SignalEvaluationRow> history(
      @PathVariable String symbol,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return facade.history(symbol, from, to);
  }

  /**
   * Paper ledger: summary (by variant and origin) + details; variant / status filter the details.
   */
  @GetMapping("/api/signals/ledger")
  public SignalFacade.Ledger ledger(
      @RequestParam(required = false) String variant,
      @RequestParam(required = false) String status) {
    return facade.ledger(upper(variant), upper(status));
  }

  /** Signal audit (section five of the post-close daily check). */
  @GetMapping("/api/signals/audit")
  public BarAuditService.Report audit(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate date) {
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
