package org.jdkxx.trader.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Valuation snapshot: market cap and valuation metrics given by the broker at one point in time. It
 * changes daily and follows the price. A negative or missing P/E for a loss-making stock is normal;
 * every field is nullable and must not be defaulted to 0 in the domain layer. Stocks and ETFs
 * differ: ETFs have no P/E or P/B but have NAV and premium, see {@code navPerShare} / {@code
 * premium}.
 *
 * <p>{@code lastPrice} is the snapshot's current price (regular-session price). It is not stored in
 * the valuation table; the account snapshot uses it as a fallback to value holdings that have no
 * bar for the day in the database. After the close it stays frozen at the regular-session close
 * until the next trading day, while {@code asOf} keeps moving with after-hours / overnight trading
 * (measured on a Sunday at 20:52 US/Eastern for SPY: {@code asOf} was the Sunday and the price
 * 764.29 equalled Friday's closing bar), so <b>{@code asOf} cannot tell which day {@code lastPrice}
 * belongs to</b>.
 */
public record ValuationSnapshot(
    Instrument instrument,
    Instant asOf,
    boolean suspended,
    BigDecimal marketCap,
    BigDecimal floatMarketCap,
    Long issuedShares,
    Long outstandingShares,
    BigDecimal pe,
    BigDecimal peTtm,
    BigDecimal pb,
    BigDecimal eps,
    BigDecimal netAssetPerShare,
    BigDecimal netAsset,
    BigDecimal netProfit,
    BigDecimal dividendTtm,
    BigDecimal dividendYieldTtm,
    BigDecimal turnoverRate,
    BigDecimal navPerShare,
    BigDecimal premium,
    BigDecimal lastPrice) {}
