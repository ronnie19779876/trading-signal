package org.jdkxx.trader.core.marketdata.quotes;

import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.MarketSession;
import org.jdkxx.trader.domain.Quote;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QuoteCacheAndStreamTest {

    private static Quote quote(String symbol, String price) {
        BigDecimal p = new BigDecimal(price);
        return new Quote(Instrument.us(symbol), MarketSession.RTH, p, null, null, p, p, p, p, p, 1, null, null, null, null,
                Instant.now(), Instant.now(), false);
    }

    @Test
    void 缓存按版本给出变化过的报价并统计推送() {
        QuoteCache cache = new QuoteCache();
        cache.accept(quote("AAPL", "1"));
        long v = cache.currentVersion();
        cache.accept(quote("MSFT", "2"));
        cache.accept(quote("AAPL", "3"));

        assertThat(cache.all()).extracting(q -> q.instrument().symbol()).containsExactly("AAPL", "MSFT");
        assertThat(cache.changedSince(v)).extracting(q -> q.instrument().symbol()).containsExactly("AAPL", "MSFT");
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
        try (QuoteStreamService stream = new QuoteStreamService(cache, () -> "st", Duration.ofHours(1))) {
            QuoteStreamService.Sink sink = new QuoteStreamService.Sink() {
                @Override
                public void quotes(List<Quote> changed) {
                    frames.add(changed);
                }

                @Override
                public void status(Object status) {
                    statuses.add(status);
                }

                @Override
                public void close() {
                }
            };
            stream.register(sink);
            assertThat(frames).hasSize(1);
            assertThat(statuses).containsExactly("st");

            stream.tick();
            assertThat(frames).hasSize(1);                  // 没变化不发

            cache.accept(quote("MSFT", "2"));
            stream.tick();
            assertThat(frames).hasSize(2);
            assertThat(frames.get(1)).extracting(q -> q.instrument().symbol()).containsExactly("MSFT");
            assertThat(stream.clientCount()).isEqualTo(1);
        }
    }
}
