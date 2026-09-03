package org.jdkxx.trader.storage.marketdata;

import java.time.Instant;

public record JobRunRow(long id, String job, String trigger, Instant startedAt, Instant finishedAt, String status, String summary) {
}
