package org.jdkxx.trader.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 一期财报。券商按"字段字典 + 数据项"两段式返回，字段随行业与版本变化，
 * 所以这里保留 fieldId 原样，不映射成固定字段名。
 */
public record FinancialReport(Instrument instrument,
                              FinancialStatement statement,
                              LocalDate periodEnd,
                              int fiscalYear,
                              String periodText,
                              String currency,
                              String accountingStandards,
                              String auditorReport,
                              List<Item> items) {

    /** 一个财务字段的取值与同比环比（百分比，可空）。 */
    public record Item(long fieldId, String name, BigDecimal value, BigDecimal yoy, BigDecimal qoq) {
    }
}
