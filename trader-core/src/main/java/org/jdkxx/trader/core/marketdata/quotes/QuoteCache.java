package org.jdkxx.trader.core.marketdata.quotes;

import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.Quote;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * 最新报价的内存缓存（不落库）+ 推送统计（总数、最近时刻、最近 60 秒每秒桶）。
 * 每条报价带递增版本号，推流按版本号取"上一帧之后变过的"。
 */
public class QuoteCache {

    private final ConcurrentHashMap<Instrument, Versioned> quotes = new ConcurrentHashMap<>();
    private final AtomicLong version = new AtomicLong();
    private final AtomicLong pushes = new AtomicLong();
    private final AtomicLongArray perSecond = new AtomicLongArray(60);
    private final AtomicLongArray perSecondStamp = new AtomicLongArray(60);
    private volatile Instant lastPushAt;

    public record Versioned(Quote quote, long version) {
    }

    public void accept(Quote q) {
        long v = version.incrementAndGet();
        quotes.put(q.instrument(), new Versioned(q, v));
        pushes.incrementAndGet();
        Instant now = q.receivedAt();
        lastPushAt = now;
        long sec = now.getEpochSecond();
        int i = (int) (sec % 60);
        if (perSecondStamp.get(i) != sec) {
            perSecondStamp.set(i, sec);
            perSecond.set(i, 0);
        }
        perSecond.incrementAndGet(i);
    }

    public Optional<Quote> get(Instrument instrument) {
        Versioned v = quotes.get(instrument);
        return Optional.ofNullable(v).map(Versioned::quote);
    }

    public List<Quote> all() {
        List<Quote> out = new ArrayList<>(quotes.size());
        quotes.values().forEach(v -> out.add(v.quote()));
        out.sort((a, b) -> a.instrument().symbol().compareTo(b.instrument().symbol()));
        return out;
    }

    /** 版本号大于 sinceVersion 的报价。 */
    public List<Quote> changedSince(long sinceVersion) {
        List<Quote> out = new ArrayList<>();
        for (Versioned v : quotes.values()) {
            if (v.version() > sinceVersion) {
                out.add(v.quote());
            }
        }
        out.sort((a, b) -> a.instrument().symbol().compareTo(b.instrument().symbol()));
        return out;
    }

    public long currentVersion() {
        return version.get();
    }

    public void remove(Collection<Instrument> instruments) {
        instruments.forEach(quotes::remove);
    }

    public void clear() {
        quotes.clear();
    }

    public int size() {
        return quotes.size();
    }

    public long totalPushes() {
        return pushes.get();
    }

    public Instant lastPushAt() {
        return lastPushAt;
    }

    /** 最近 60 秒的推送条数。 */
    public long pushesLastMinute() {
        long now = Instant.now().getEpochSecond();
        long sum = 0;
        for (int i = 0; i < 60; i++) {
            if (now - perSecondStamp.get(i) < 60) {
                sum += perSecond.get(i);
            }
        }
        return sum;
    }

    Map<Instrument, Versioned> snapshot() {
        return Map.copyOf(quotes);
    }
}
