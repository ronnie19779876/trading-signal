package org.jdkxx.trader.storage.marketdata;

import java.time.LocalDate;
import org.jdkxx.trader.domain.IndexCode;

public record ConstituentRow(
    IndexCode index,
    long instrumentId,
    String sector,
    String subIndustry,
    String classification,
    LocalDate since,
    LocalDate until,
    String source) {}
