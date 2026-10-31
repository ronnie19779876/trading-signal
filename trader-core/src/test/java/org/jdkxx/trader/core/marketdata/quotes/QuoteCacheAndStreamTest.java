package org.jdkxx.trader.core.marketdata.quotes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.MarketSession;
import org.jdkxx.trader.domain.Quote;
import org.junit.jupiter.api.Test;

class QuoteCacheAndStreamTest {

  private static Quote quote(String symbol, String price) {
    BigDecimal p = new BigDecimal(price);
    return new Quote(
        Instrument.us(symbol),
        MarketSession.RTH,
        p,
        null,
        null,
        p,
        p,
        p,
        p,
        p,
        p,
        1,
        null,
        null,
        null,
        null,
        Instant.now(),
        Instant.now(),
        false);
  }

  @Test
  void 缓存按版本给出变化过的报价并统计推送() {
    QuoteCache cache = new QuoteCache();
    cache.accept(quote("AAPL", "1"));
    long v = cache.currentVersion();
    cache.accept(quote("MSFT", "2"));
    cache.accept(quote("AAPL", "3"));

    assertThat(cache.all())
        .extracting(q -> q.instrument().symbol())
        .containsExactly("AAPL", "MSFT");
    assertThat(cache.changedSince(v))
        .extracting(q -> q.instrument().symbol())
        .containsExactly("AAPL", "MSFT");
    assertThat(cache.get(Instrument.us("AAPL")).orElseThrow().price()).isEqualByComparingTo("3");
    assertThat(cache.totalPushes()).isEqualTo(3);
    assertThat(cache.pushesLastMinute()).isEqualTo(3);
    assertThat(cache.lastPushAt()).isNotNull();
  }

  @Test
  void 推流注册时先发全量之后只发变化() throws Exception {
    QuoteCache cache = new QuoteCache();
    cache.accept(quote("AAPL", "1"));
    List<List<Quote>> frames = new ArrayList<>();
    List<Object> statuses = new ArrayList<>();
    try (QuoteStreamService stream =
        new QuoteStreamService(cache, () -> "st", Duration.ofHours(1))) {
      QuoteStreamService.Sink sink =
          new QuoteStreamService.Sink() {
            @Override
            public void quotes(List<Quote> changed) {
              frames.add(changed);
            }

            @Override
            public void status(Object status) {
              statuses.add(status);
            }

            @Override
            public void close() {}
          };
      stream.register(sink);
      assertThat(frames).hasSize(1);
      assertThat(statuses).containsExactly("st");

      stream.tick();
      assertThat(frames).hasSize(1); // nothing changed, nothing sent

      cache.accept(quote("MSFT", "2"));
      stream.tick();
      assertThat(frames).hasSize(2);
      assertThat(frames.get(1)).extracting(q -> q.instrument().symbol()).containsExactly("MSFT");
      assertThat(stream.clientCount()).isEqualTo(1);
    }
  }

  @Test
  void 推流与写入并发时不丢最后一条报价() {
    // Found in the 2026-10-31 review: accept() bumped the version before putting the entry, so a
    // tick could read the new version, scan the map before the put, and move its cursor past a
    // quote it never sent; that quote stayed missing until the instrument updated again. Each round
    // writes one quote per instrument, waits for two full ticks after the writes, and then the sink
    // must hold exactly the cache's latest quote for every instrument
    assertTimeoutPreemptively(
        Duration.ofSeconds(30),
        () -> {
          QuoteCache cache = new QuoteCache();
          Map<Instrument, Quote> seen = new ConcurrentHashMap<>();
          List<Instrument> instruments = new ArrayList<>();
          for (int k = 0; k < 8; k++) {
            instruments.add(Instrument.us("S" + k));
          }
          AtomicLong ticks = new AtomicLong();
          long misses = 0;
          long rounds = 0;
          try (QuoteStreamService stream =
              new QuoteStreamService(cache, () -> "st", Duration.ofHours(1))) {
            stream.register(
                new QuoteStreamService.Sink() {
                  @Override
                  public void quotes(List<Quote> changed) {
                    changed.forEach(q -> seen.put(q.instrument(), q));
                  }

                  @Override
                  public void status(Object status) {}

                  @Override
                  public void close() {}
                });
            Thread ticker =
                new Thread(
                    () -> {
                      while (!Thread.currentThread().isInterrupted()) {
                        stream.tick();
                        ticks.incrementAndGet();
                      }
                    },
                    "test-ticker");
            ticker.setDaemon(true);
            ticker.start();
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            try {
              while (rounds < 20_000 && System.nanoTime() < deadline) {
                rounds++;
                for (Instrument i : instruments) {
                  cache.accept(quote(i.symbol(), Long.toString(rounds)));
                }
                long t = ticks.get();
                while (ticks.get() < t + 2) {
                  Thread.onSpinWait();
                }
                for (Instrument i : instruments) {
                  if (seen.get(i) != cache.get(i).orElseThrow()) {
                    misses++;
                  }
                }
              }
            } finally {
              ticker.interrupt();
              ticker.join();
            }
          }
          assertThat(rounds).isGreaterThan(100);
          assertThat(misses).as("%d 轮里推流漏掉的最新报价条数", rounds).isZero();
        });
  }
}
