package org.jdkxx.trader.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Real-time quote (in-memory object, never persisted). {@code price}/{@code change}/{@code
 * changeRate} are the session-dependent "effective price": RTH and CLOSED use the regular-session
 * curPrice, PRE / AFTER / OVERNIGHT use the matching sub-structure (outside regular hours the
 * broker freezes curPrice and only updates the sub-structures).
 *
 * @param rthPrice regular-session price (broker curPrice; frozen at the last close outside regular
 *     hours)
 * @param lastClose the broker's "previous close": <b>the regular-session close of the prior trading
 *     day</b>. Outside regular hours it does not advance with the session and is still the previous
 *     close of the last regular session, so never use it as the base for pre/after-market changes
 * @param referenceClose the base actually used by the current change / changeRate: lastClose during
 *     the regular session and when closed, rthPrice (the last regular-session close) pre-market,
 *     after hours and overnight. Use this one to display a "reference price"
 * @param quoteTime broker quote time (the regular session's updateTime; outside regular hours it
 *     may still be the closing time)
 */
public record Quote(
    Instrument instrument,
    MarketSession session,
    BigDecimal price,
    BigDecimal change,
    BigDecimal changeRate,
    BigDecimal open,
    BigDecimal high,
    BigDecimal low,
    BigDecimal rthPrice,
    BigDecimal lastClose,
    BigDecimal referenceClose,
    long volume,
    BigDecimal turnover,
    SessionQuote preMarket,
    SessionQuote afterMarket,
    SessionQuote overnight,
    Instant quoteTime,
    Instant receivedAt,
    boolean suspended) {

  public Quote {
    Objects.requireNonNull(instrument, "instrument");
    session = session == null ? MarketSession.CLOSED : session;
    receivedAt = receivedAt == null ? Instant.now() : receivedAt;
  }

  /** One set of extended-hours data. */
  public record SessionQuote(
      BigDecimal price, BigDecimal change, BigDecimal changeRate, long volume) {}
}
