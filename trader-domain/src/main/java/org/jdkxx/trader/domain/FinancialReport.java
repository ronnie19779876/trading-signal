package org.jdkxx.trader.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * One period of a financial statement. The broker returns it in two parts ("field dictionary + data
 * items") and the fields vary by industry and version, so the raw {@code fieldId} is kept instead
 * of being mapped to fixed field names. A field id only has meaning together with its statement
 * type (8001 is total revenue in the income statement but total assets in the balance sheet). The
 * annual report and the Q4 report share the same period end, so {@code periodText} is part of the
 * identity.
 */
public record FinancialReport(
    Instrument instrument,
    FinancialStatement statement,
    LocalDate periodEnd,
    int fiscalYear,
    String periodText,
    String currency,
    String accountingStandards,
    String auditorReport,
    List<Item> items) {

  /**
   * Value of one financial field with its year-over-year and quarter-over-quarter change (percent,
   * nullable).
   */
  public record Item(long fieldId, String name, BigDecimal value, BigDecimal yoy, BigDecimal qoq) {}
}
