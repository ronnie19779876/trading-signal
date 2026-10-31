package org.jdkxx.trader.core.marketdata.valuation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * When a saved model is read back, which day its present value is discounted to.
 *
 * <p>Before 3.1.1 it used "the asOf frozen in the database" with "today's price": the discount
 * period started from the old valuation date while the upside compared against today's close, so
 * the two dates disagreed and the error grew the longer the model was kept (found in the 2026-09-25
 * full-project review).
 */
class SotpValuationDateTest {

  @Test
  void 现价更新到今天就折算到今天() {
    assertThat(SotpService.valuationDate(LocalDate.of(2026, 1, 2), LocalDate.of(2026, 9, 24), 2030))
        .isEqualTo(LocalDate.of(2026, 9, 24));
  }

  @Test
  void 现价还停在基准日之前就用基准日() {
    assertThat(
            SotpService.valuationDate(LocalDate.of(2026, 9, 24), LocalDate.of(2026, 9, 11), 2030))
        .isEqualTo(LocalDate.of(2026, 9, 24));
    assertThat(SotpService.valuationDate(LocalDate.of(2026, 9, 24), null, 2030))
        .isEqualTo(LocalDate.of(2026, 9, 24));
  }

  /**
   * When the price date has already passed the target year, fall back to asOf: SotpAssumptions
   * rejects a target year earlier than the valuation date's year.
   */
  @Test
  void 现价日跨过目标年就退回基准日() {
    assertThat(SotpService.valuationDate(LocalDate.of(2026, 1, 2), LocalDate.of(2031, 3, 1), 2030))
        .isEqualTo(LocalDate.of(2026, 1, 2));
    assertThat(
            SotpService.valuationDate(LocalDate.of(2026, 1, 2), LocalDate.of(2030, 12, 31), 2030))
        .as("同一年内照常前移")
        .isEqualTo(LocalDate.of(2030, 12, 31));
  }
}
