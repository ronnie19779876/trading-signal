package org.jdkxx.trader.domain.selection;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class QuarterAvailabilityTest {

  private static QuarterFacts q(String end, boolean q4) {
    return new QuarterFacts(
        LocalDate.parse(end),
        q4,
        QuarterFacts.Template.STANDARD,
        null,
        1.0,
        1.0,
        1.0,
        1.0,
        1.0,
        1.0);
  }

  private static LocalDate d(String s) {
    return LocalDate.parse(s);
  }

  /**
   * Nearest by date: the first announcement within 75 days after the period end, ignoring period
   * labels (which sidesteps the retailers' labels being one fiscal year off).
   */
  @Test
  void 期末后七十五天内第一个公告日() {
    List<QuarterFacts> r =
        QuarterAvailability.resolve(
            List.of(q("2025-01-31", true)),
            List.of(d("2025-03-05"), d("2025-05-28"), d("2026-03-10")),
            new TreeSet<>());

    assertThat(r.getFirst().availableOn()).isEqualTo(d("2025-03-05"));
  }

  /**
   * When a quarter lacks its announcement date it must not be matched to the next quarter's (the
   * next one comes at the earliest about 104 days after the period end).
   */
  @Test
  void 缺期不错配到下一季_走兜底() {
    List<QuarterFacts> r =
        QuarterAvailability.resolve(
            List.of(q("2024-03-31", false)), List.of(d("2024-07-14")), new TreeSet<>());

    assertThat(r.getFirst().availableOn()).isEqualTo(d("2024-05-10")); // 03-31 + 40 days
  }

  /**
   * The fallback is max(statutory deadline, largest observed lag for the instrument): this stock
   * usually announces after 55 days, so the 40-day rule alone would look ahead.
   */
  @Test
  void 兜底取法定期限与已观测最大滞后的较晚者() {
    List<QuarterFacts> r =
        QuarterAvailability.resolve(
            List.of(q("2024-03-31", false), q("2024-06-30", false)),
            List.of(d("2024-08-24")),
            new TreeSet<>());

    assertThat(r.get(1).availableOn()).isEqualTo(d("2024-08-24"));
    assertThat(r.get(0).availableOn())
        .isEqualTo(d("2024-05-27")); // 03-31 + 55 days = 05-25 (Saturday), rolled forward to 05-27
  }

  @Test
  void 第四季法定期限六十天_落在非交易日按日历顺延() {
    TreeSet<LocalDate> days = new TreeSet<>(List.of(d("2026-03-02"), d("2026-03-03")));
    List<QuarterFacts> r =
        QuarterAvailability.resolve(List.of(q("2025-12-31", true)), List.of(), days);

    assertThat(r.getFirst().availableOn())
        .isEqualTo(d("2026-03-02")); // 12-31 + 60 = 03-01 (Sunday)
  }

  @Test
  void 早于期末的公告日不认() {
    List<QuarterFacts> r =
        QuarterAvailability.resolve(
            List.of(q("2025-12-31", false)), List.of(d("2025-09-16")), new TreeSet<>());

    assertThat(r.getFirst().availableOn()).isEqualTo(d("2026-02-09")); // fallback of 40 days
  }
}
