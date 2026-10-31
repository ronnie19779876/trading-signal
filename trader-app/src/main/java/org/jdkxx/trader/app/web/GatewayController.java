package org.jdkxx.trader.app.web;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import org.jdkxx.trader.core.gateway.GatewayService;
import org.jdkxx.trader.core.gateway.GatewayViews;
import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.domain.InstrumentInfo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Gateway status, manual control, accounts (masked), contract lookup, event timeline. Same-origin,
 * no authentication, loopback only; connect/disconnect are POSTs and go through {@link
 * LocalRequestGuardFilter}.
 */
@RestController
@RequestMapping("/api/gateways")
public class GatewayController {

  private final GatewayService service;

  public GatewayController(GatewayService service) {
    this.service = service;
  }

  @GetMapping
  public List<GatewayViews.GatewayView> list() {
    return service.statuses();
  }

  @GetMapping("/events")
  public List<GatewayViews.EventView> events(@RequestParam(defaultValue = "50") int limit) {
    return service.events(limit);
  }

  @GetMapping("/{broker}")
  public GatewayViews.GatewayView one(@PathVariable String broker) {
    return service.status(broker(broker));
  }

  @PostMapping("/{broker}/connect")
  public GatewayViews.GatewayView connect(@PathVariable String broker) {
    return service.connect(broker(broker));
  }

  @PostMapping("/{broker}/disconnect")
  public GatewayViews.GatewayView disconnect(@PathVariable String broker) {
    return service.disconnect(broker(broker));
  }

  @GetMapping("/{broker}/accounts")
  public CompletableFuture<List<GatewayViews.AccountView>> accounts(@PathVariable String broker) {
    return service.accounts(broker(broker));
  }

  @GetMapping("/{broker}/instruments")
  public CompletableFuture<List<InstrumentInfo>> instruments(
      @PathVariable String broker, @RequestParam String symbol) {
    return service.lookup(broker(broker), symbol);
  }

  private static Broker broker(String raw) {
    try {
      return Broker.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("未知券商：" + raw + "（可选 IBKR / FUTU）");
    }
  }
}
