package org.jdkxx.trader.domain;

import java.time.LocalDate;

/**
 * One day of the trading calendar. {@code kind} is the broker's type (0 full day, 1 morning only, 2
 * afternoon only).
 */
public record TradingDay(Market market, LocalDate date, int kind) {}
