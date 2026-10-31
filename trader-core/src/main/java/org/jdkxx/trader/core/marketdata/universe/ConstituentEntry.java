package org.jdkxx.trader.core.marketdata.universe;

import java.time.LocalDate;
import org.jdkxx.trader.domain.IndexCode;

/** One row as given by a constituent source. */
public record ConstituentEntry(
    IndexCode index,
    String symbol,
    String name,
    String sector,
    String subIndustry,
    LocalDate addedDate) {}
