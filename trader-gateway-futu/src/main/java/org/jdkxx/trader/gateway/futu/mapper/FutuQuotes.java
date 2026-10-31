package org.jdkxx.trader.gateway.futu.mapper;

import com.futu.openapi.pb.QotCommon;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.MarketSession;
import org.jdkxx.trader.domain.Quote;

/**
 * Futu BasicQot → domain quote. Measured (pre-market): curPrice / volume / updateTime stay frozen
 * at the last close and only the preMarket sub-structure updates, so the effective price is taken
 * according to the session.
 */
public final class FutuQuotes {

  private static final ZoneId ET = ZoneId.of("America/New_York");
  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSS]");

  private FutuQuotes() {}

  public static Quote toQuote(QotCommon.BasicQot q, MarketSession session, Instant receivedAt) {
    Instrument instrument =
        new Instrument(
            FutuSecurities.market(q.getSecurity().getMarket()), q.getSecurity().getCode());
    BigDecimal cur =
        FutuNumbers.required(q.getCurPrice(), 6, "curPrice", instrument, q.getUpdateTime());
    BigDecimal lastClose = q.hasLastClosePrice() ? price(q.getLastClosePrice()) : null;
    Quote.SessionQuote pre = q.hasPreMarket() ? session(q.getPreMarket()) : null;
    Quote.SessionQuote after = q.hasAfterMarket() ? session(q.getAfterMarket()) : null;
    Quote.SessionQuote overnight = q.hasOvernight() ? session(q.getOvernight()) : null;

    BigDecimal price = cur;
    BigDecimal change = lastClose == null ? null : cur.subtract(lastClose);
    BigDecimal changeRate =
        lastClose == null || lastClose.signum() == 0
            ? null
            : change.multiply(BigDecimal.valueOf(100)).divide(lastClose, 4, RoundingMode.HALF_UP);
    Quote.SessionQuote effective =
        switch (session) {
          case PRE -> pre;
          case AFTER -> after;
          case OVERNIGHT -> overnight != null ? overnight : after;
          default -> null;
        };
    // Reference price: in the regular session it is the broker's "previous close". In pre-market,
    // after-hours and overnight the broker computes the change against the last regular close (the
    // frozen curPrice), while lastClose is still the previous close of that earlier regular
    // session; using it as the base would give a completely different change. Only when the session
    // sub-structure has a positive price do we switch to it; otherwise the regular-session values
    // (against lastClose) are kept
    BigDecimal referenceClose = lastClose;
    if (effective != null && effective.price() != null && effective.price().signum() > 0) {
      price = effective.price();
      change = effective.change();
      changeRate = effective.changeRate();
      referenceClose = cur;
    }
    return new Quote(
        instrument,
        session,
        price,
        change,
        changeRate,
        price(q.getOpenPrice()),
        price(q.getHighPrice()),
        price(q.getLowPrice()),
        cur,
        lastClose,
        referenceClose,
        q.getVolume(),
        turnover(q),
        pre,
        after,
        overnight,
        quoteTime(q),
        receivedAt,
        q.getIsSuspended());
  }

  static Quote.SessionQuote session(QotCommon.PreAfterMarketData d) {
    return new Quote.SessionQuote(
        price(d.getPrice()),
        d.hasChangeVal() ? price(d.getChangeVal()) : null,
        d.hasChangeRate() ? FutuNumbers.orNull(d.getChangeRate(), 4) : null,
        d.hasVolume() ? d.getVolume() : 0);
  }

  static Instant quoteTime(QotCommon.BasicQot q) {
    if (q.hasUpdateTimestamp() && q.getUpdateTimestamp() > 0) {
      return Instant.ofEpochMilli((long) (q.getUpdateTimestamp() * 1000));
    }
    try {
      return LocalDateTime.parse(q.getUpdateTime(), TIME).atZone(ET).toInstant();
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** Nullable price: a non-finite value becomes null (curPrice is required, see toQuote). */
  static BigDecimal price(double v) {
    return FutuNumbers.orNull(v, 6);
  }

  private static BigDecimal turnover(QotCommon.BasicQot q) {
    BigDecimal t = q.hasTurnover() ? FutuNumbers.orNull(q.getTurnover()) : null;
    return t == null ? null : t.setScale(2, RoundingMode.HALF_UP);
  }

  /**
   * Determines the session from getGlobalState's marketUS state name; falls back to the US/Eastern
   * clock when it is unavailable.
   */
  public static MarketSession session(String marketStateName, ZonedDateTime nowEt) {
    if (marketStateName != null) {
      switch (marketStateName) {
        case "PreMarketBegin", "PreMarketEnd" -> {
          return MarketSession.PRE;
        }
        case "Morning", "Afternoon", "Rest" -> {
          return MarketSession.RTH;
        }
        case "AfterHoursBegin" -> {
          return MarketSession.AFTER;
        }
        case "NightOpen" -> {
          return MarketSession.OVERNIGHT;
        }
        case "AfterHoursEnd",
            "NightEnd",
            "Closed",
            "Auction",
            "WaitingOpen",
            "FUTU_SWITCH_DATE" -> {
          return MarketSession.CLOSED;
        }
        default -> {
          // Unknown names fall back to the clock
        }
      }
    }
    return byClock(nowEt == null ? ZonedDateTime.now(ET) : nowEt.withZoneSameInstant(ET));
  }

  /**
   * Clock fallback: weekdays only, pre-market 04:00-09:30, regular 09:30-16:00, after-hours
   * 16:00-20:00 (US/Eastern); exchange holidays are not known here.
   */
  static MarketSession byClock(ZonedDateTime et) {
    if (et.getDayOfWeek().getValue() >= 6) {
      return MarketSession.CLOSED;
    }
    LocalTime t = et.toLocalTime();
    if (!t.isBefore(LocalTime.of(4, 0)) && t.isBefore(LocalTime.of(9, 30))) {
      return MarketSession.PRE;
    }
    if (!t.isBefore(LocalTime.of(9, 30)) && t.isBefore(LocalTime.of(16, 0))) {
      return MarketSession.RTH;
    }
    if (!t.isBefore(LocalTime.of(16, 0)) && t.isBefore(LocalTime.of(20, 0))) {
      return MarketSession.AFTER;
    }
    return MarketSession.CLOSED;
  }
}
