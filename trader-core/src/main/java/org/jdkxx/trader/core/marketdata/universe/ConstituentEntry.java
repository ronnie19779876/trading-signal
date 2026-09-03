package org.jdkxx.trader.core.marketdata.universe;

import org.jdkxx.trader.domain.IndexCode;

import java.time.LocalDate;

/** 成分股来源给出的一行。 */
public record ConstituentEntry(IndexCode index, String symbol, String name, String sector, String subIndustry, LocalDate addedDate) {
}
