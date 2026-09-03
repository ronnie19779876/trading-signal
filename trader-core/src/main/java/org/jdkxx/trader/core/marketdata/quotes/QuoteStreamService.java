package org.jdkxx.trader.core.marketdata.quotes;

import org.jdkxx.trader.domain.Quote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * SSE 推流：每个客户端记住"上一帧的版本号"，每 interval 把之后变过的报价合并成一帧；15 秒发一次状态。
 * 与 Web 框架解耦：客户端是一个 {@link Sink}（app 层用 SseEmitter 实现）。
 */
public class QuoteStreamService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(QuoteStreamService.class);

    public interface Sink {
        void quotes(List<Quote> changed) throws Exception;

        void status(Object status) throws Exception;

        void close();
    }

    private static final class Client {
        final Sink sink;
        long version;
        long lastStatusAt;

        Client(Sink sink, long version) {
            this.sink = sink;
            this.version = version;
        }
    }

    private final QuoteCache cache;
    private final Supplier<Object> status;
    private final List<Client> clients = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "quote-stream");
        t.setDaemon(true);
        return t;
    });

    public QuoteStreamService(QuoteCache cache, Supplier<Object> status, Duration interval) {
        this.cache = cache;
        this.status = status;
        long ms = Math.max(200, interval.toMillis());
        scheduler.scheduleWithFixedDelay(this::tick, ms, ms, TimeUnit.MILLISECONDS);
    }

    /** 注册后立刻发一帧全量。 */
    public void register(Sink sink) {
        Client c = new Client(sink, 0);
        clients.add(c);
        try {
            sink.status(status.get());
            List<Quote> all = cache.all();
            c.version = cache.currentVersion();
            if (!all.isEmpty()) {
                sink.quotes(all);
            }
            c.lastStatusAt = System.currentTimeMillis();
        } catch (Exception e) {
            drop(c);
        }
    }

    public void unregister(Sink sink) {
        clients.removeIf(c -> c.sink == sink);
    }

    public int clientCount() {
        return clients.size();
    }

    void tick() {
        long now = System.currentTimeMillis();
        long current = cache.currentVersion();
        Object st = null;
        for (Client c : clients) {
            try {
                if (current > c.version) {
                    List<Quote> changed = cache.changedSince(c.version);
                    c.version = current;
                    if (!changed.isEmpty()) {
                        c.sink.quotes(changed);
                    }
                }
                if (now - c.lastStatusAt >= 15_000) {
                    if (st == null) {
                        st = status.get();
                    }
                    c.sink.status(st);
                    c.lastStatusAt = now;
                }
            } catch (Exception e) {
                log.debug("SSE 客户端断开：{}", e.toString());
                drop(c);
            }
        }
    }

    private void drop(Client c) {
        clients.remove(c);
        try {
            c.sink.close();
        } catch (RuntimeException ignored) {
            // ignore
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        List<Client> all = new ArrayList<>(clients);
        clients.clear();
        all.forEach(c -> c.sink.close());
    }
}
