package org.jdkxx.trader.app.web;

import java.util.List;
import java.util.Map;
import org.jdkxx.trader.core.marketdata.valuation.SotpBasisService;
import org.jdkxx.trader.core.marketdata.valuation.SotpService;
import org.jdkxx.trader.domain.valuation.SotpResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sum-of-the-parts valuation (SOTP). A read-only basis plus storing and trial-calculating manual
 * assumptions; <b>not linked to signals or the paper ledger</b>.
 *
 * <p>The broker only provides consolidated statements, with no segment volume/price and no
 * consensus estimates, so the assumptions for each business line must be filled in by the user.
 * This API fills in the company-level basis automatically, does the arithmetic and the consistency
 * checks; it does not forecast and gives no advice. Design: docs/ARCHITECTURE.md §21.
 */
@RestController
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class ValuationController {

  private final SotpService sotp;

  public ValuationController(SotpService sotp) {
    this.sotp = sotp;
  }

  /**
   * The automatically filled company-level basis: current price, share count under three
   * definitions, net-cash breakdown, TTM revenue and net margin, P/E baseline, applicability.
   */
  @GetMapping("/api/valuation/sotp/{symbol}/inputs")
  public SotpBasisService.Basis inputs(@PathVariable String symbol) {
    return sotp.inputs(symbol);
  }

  /**
   * The saved models of this symbol (each with its computed result), most recently updated first.
   */
  @GetMapping("/api/valuation/sotp/{symbol}")
  public List<SotpService.SavedModel> models(@PathVariable String symbol) {
    return sotp.models(symbol);
  }

  /**
   * Trial calculation, not persisted. The two-segment path calc/{symbol} cannot collide with the
   * single-segment {symbol} above.
   */
  @PostMapping("/api/valuation/sotp/calc/{symbol}")
  public SotpResult calc(
      @PathVariable String symbol, @RequestBody SotpService.ModelRequest request) {
    return sotp.calc(symbol, request);
  }

  /** Saves / updates a model (overwrites by name). */
  @PostMapping("/api/valuation/sotp/{symbol}")
  public SotpService.SavedModel save(
      @PathVariable String symbol, @RequestBody SotpService.ModelRequest request) {
    return sotp.save(symbol, request);
  }

  @DeleteMapping("/api/valuation/sotp/{id}")
  public Map<String, Object> delete(@PathVariable long id) {
    sotp.delete(id);
    return Map.of("deleted", id);
  }
}
