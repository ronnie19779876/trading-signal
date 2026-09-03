package org.jdkxx.trader.domain;

import java.time.Instant;
import java.util.List;

/** 富途历史 K 线额度：7 天滚动窗口内已用 / 剩余，以及占用明细。 */
public record HistoryQuota(int used, int remain, List<Item> items) {

    public record Item(Instrument instrument, String name, Instant requestedAt) {
    }

    public int total() {
        return used + remain;
    }
}
