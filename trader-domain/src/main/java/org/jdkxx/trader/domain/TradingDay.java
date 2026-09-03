package org.jdkxx.trader.domain;

import java.time.LocalDate;

/** 交易日历的一天。kind 为券商给的类型（0 全天，1 只有上午，2 只有下午）。 */
public record TradingDay(Market market, LocalDate date, int kind) {
}
