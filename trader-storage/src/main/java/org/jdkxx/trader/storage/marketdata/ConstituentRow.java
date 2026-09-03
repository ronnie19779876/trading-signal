package org.jdkxx.trader.storage.marketdata;

import org.jdkxx.trader.domain.IndexCode;

import java.time.LocalDate;

public record ConstituentRow(IndexCode index, long instrumentId, String sector, String subIndustry, String classification,
                             LocalDate since, LocalDate until, String source) {
}
