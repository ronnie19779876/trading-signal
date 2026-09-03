package org.jdkxx.trader.app.web;

import org.jdkxx.trader.core.marketdata.quotes.QuoteCache;
import org.jdkxx.trader.core.marketdata.quotes.QuoteStreamService;
import org.jdkxx.trader.core.marketdata.quotes.QuoteSubscriptionService;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.Quote;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 实时报价（不落库）：快照、SSE 推流、订阅状态与手工控制。
 */
@RestController
@RequestMapping("/api/quotes")
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class QuotesController {

    private static final long SSE_TIMEOUT_MS = 30L * 60 * 1000;

    private final QuoteCache cache;
    private final QuoteSubscriptionService subscriptions;
    private final QuoteStreamService stream;

    public QuotesController(QuoteCache cache, QuoteSubscriptionService subscriptions, QuoteStreamService stream) {
        this.cache = cache;
        this.subscriptions = subscriptions;
        this.stream = stream;
    }

    public record StatusView(boolean enabled, boolean paused, int desired, int subscribed, int deferredUnsubscribe,
                             Object quota, Instant lastReconcileAt, String lastError, int cached, long totalPushes,
                             long pushesLastMinute, Instant lastPushAt, int streamClients) {
    }

    @GetMapping
    public List<Quote> all() {
        return cache.all();
    }

    @GetMapping("/status")
    public StatusView status() {
        QuoteSubscriptionService.Status s = subscriptions.status();
        return new StatusView(s.enabled(), s.paused(), s.desired(), s.subscribed(), s.deferredUnsubscribe(), s.quota(),
                s.lastReconcileAt(), s.lastError(), cache.size(), cache.totalPushes(), cache.pushesLastMinute(),
                cache.lastPushAt(), stream.clientCount());
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        QuoteStreamService.Sink sink = new QuoteStreamService.Sink() {
            @Override
            public void quotes(List<Quote> changed) throws Exception {
                emitter.send(SseEmitter.event().name("quotes").data(changed, MediaType.APPLICATION_JSON));
            }

            @Override
            public void status(Object status) throws Exception {
                emitter.send(SseEmitter.event().name("status").data(QuotesController.this.status(), MediaType.APPLICATION_JSON));
            }

            @Override
            public void close() {
                try {
                    emitter.complete();
                } catch (RuntimeException ignored) {
                    // 已断开
                }
            }
        };
        emitter.onCompletion(() -> stream.unregister(sink));
        emitter.onTimeout(() -> stream.unregister(sink));
        emitter.onError(e -> stream.unregister(sink));
        stream.register(sink);
        return emitter;
    }

    @GetMapping("/{symbol}")
    public Quote one(@PathVariable String symbol) {
        return cache.get(Instrument.us(symbol)).orElseThrow(() -> new NoSuchElementException(symbol.toUpperCase() + " 没有实时报价（未订阅或尚未收到推送）"));
    }

    @PostMapping("/subscriptions/reconcile")
    public QuoteSubscriptionService.Result reconcile() {
        return subscriptions.reconcile();
    }

    @PostMapping("/subscriptions/pause")
    public QuoteSubscriptionService.Result pause() {
        return subscriptions.pause();
    }

    @PostMapping("/subscriptions/resume")
    public QuoteSubscriptionService.Result resume() {
        return subscriptions.resume();
    }

    @GetMapping("/subscriptions")
    public Map<String, Object> subscriptionsView() {
        QuoteSubscriptionService.Status s = subscriptions.status();
        return Map.of("desired", s.desired(), "subscribed", s.subscribed(), "paused", s.paused());
    }
}
