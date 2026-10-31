package org.jdkxx.trader.core.marketdata;

import java.time.Duration;

/**
 * Configuration fixture for tests. {@link MarketDataProperties} is a positional record, so every
 * new property means editing the constructor call in every test (the build has broken three times
 * because of it); this keeps it in one place.
 *
 * <p>Sub-records are filled with their real defaults wherever possible: the ones left null only
 * blow up when read, and they blow up in unrelated tests (universe left null: when the constituent
 * guard was added on 2026-09-25, 6 tests hit NPE at once).
 */
public final class TestProperties {

  private TestProperties() {}

  public static MarketDataProperties defaults() {
    return new MarketDataProperties(
        new MarketDataProperties.Universe("", "", "", true, "0 30 6 * * SAT", 200, 5, 5, 3, "test"),
        null,
        new MarketDataProperties.Refresh(90, 65, true, 1000, 5, 5),
        null,
        null,
        null,
        new MarketDataProperties.Fundamentals(
            true, 400, 12, Duration.ofDays(30), Duration.ofDays(180)),
        false,
        "",
        "",
        "",
        "",
        "America/New_York");
  }
}
