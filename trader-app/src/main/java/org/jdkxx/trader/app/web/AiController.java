package org.jdkxx.trader.app.web;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import org.jdkxx.trader.core.signal.ai.AiAnalysisFacade;
import org.jdkxx.trader.storage.signal.AiAnalysisRow;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Model second opinion (phase 4 step 4): manual analysis (billed), queries, usage. Only wired when
 * storage is enabled.
 */
@RestController
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class AiController {

  private final AiAnalysisFacade facade;

  public AiController(AiAnalysisFacade facade) {
    this.facade = facade;
  }

  /**
   * Manual analysis of one symbol on one day (synchronous, about 15 seconds, billed; counts toward
   * the daily limit; an existing conclusion for the same input is reused). 409 when that day is not
   * evaluable.
   */
  @PostMapping("/api/ai/analyses")
  public AiAnalysisRow analyze(
      @RequestParam String symbol,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate date) {
    return facade.analyze(symbol, date);
  }

  @GetMapping("/api/ai/analyses")
  public List<AiAnalysisRow> list(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String symbol,
      @RequestParam(defaultValue = "100") int limit) {
    return facade.list(
        from,
        to,
        status == null || status.isBlank() ? null : status.toUpperCase(Locale.ROOT),
        symbol == null || symbol.isBlank() ? null : symbol,
        limit);
  }

  @GetMapping("/api/ai/analyses/{id}")
  public AiAnalysisRow find(@PathVariable long id) {
    return facade.find(id);
  }

  @GetMapping("/api/ai/usage")
  public AiAnalysisFacade.Usage usage(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return facade.usage(from, to);
  }
}
