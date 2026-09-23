package org.jdkxx.trader.storage.valuation;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 一套分部估值假设的库内表示。{@code segments} 是 jsonb 原文，反序列化交给上层
 * （storage 不认领域对象的 JSON 结构，避免存储层跟着业务结构改）。
 */
public record SotpModelRow(Long id, long instrumentId, String symbol, String name, LocalDate asOf, int targetYear,
                           BigDecimal discountRate, BigDecimal targetShares, BigDecimal targetNetCash,
                           String segments, String note, Instant createdAt, Instant updatedAt) {
}
