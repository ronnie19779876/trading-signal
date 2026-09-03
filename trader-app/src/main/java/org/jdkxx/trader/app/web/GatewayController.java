package org.jdkxx.trader.app.web;

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

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * 网关状态、手工控制、账户（脱敏）、合约查询、事件时间线。同源、无鉴权、只监听回环。
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
    public CompletableFuture<List<InstrumentInfo>> instruments(@PathVariable String broker, @RequestParam String symbol) {
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
