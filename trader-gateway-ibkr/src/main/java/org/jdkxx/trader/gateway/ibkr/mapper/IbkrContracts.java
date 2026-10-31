package org.jdkxx.trader.gateway.ibkr.mapper;

import com.ib.client.Contract;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.Market;

/**
 * Domain instrument to TWS API contract. This is one of the only entry points through which
 * com.ib.client types come into this module.
 *
 * <p>US stocks use SMART routing; primaryExch disambiguates identically named listings (error 200).
 * "NASDAQ" as primaryExch has been verified against a real IB Gateway (AAPL resolves to conId
 * 265598), so "ISLAND" is not needed.
 */
public final class IbkrContracts {

  public static final String DEFAULT_US_PRIMARY_EXCHANGE = "NASDAQ";

  private IbkrContracts() {}

  public static Contract stock(Instrument instrument, String primaryExchange) {
    if (instrument.market() != Market.US) {
      throw new IllegalArgumentException("盈透侧目前只映射美股，收到：" + instrument);
    }
    Contract contract = new Contract();
    // IBKR writes class shares with a space (BRK B), we and Futu use a dot (BRK.B); without the
    // swap the lookup finds no such instrument (measured 2026-10-10)
    contract.symbol(instrument.symbol().replace('.', ' '));
    contract.secType("STK");
    contract.exchange("SMART");
    contract.currency("USD");
    if (primaryExchange != null && !primaryExchange.isBlank()) {
      contract.primaryExch(primaryExchange);
    }
    return contract;
  }

  /**
   * Locate by conId (for historical data): a conId uniquely identifies the contract, so SMART as
   * exchange is enough.
   */
  public static Contract byConId(long conId) {
    Contract contract = new Contract();
    contract.conid(Math.toIntExact(conId));
    contract.exchange("SMART");
    return contract;
  }

  /**
   * Contract for historical data: conId plus the primary exchange, falling back to SMART when the
   * primary exchange is unknown. SMART cannot be used across the board: SMART history only starts
   * at the most recent exchange transfer (see {@code IbkrGateway#splitAdjustedDailyBars}).
   */
  public static Contract historical(long conId, String primaryExchange) {
    Contract contract = byConId(conId);
    if (primaryExchange != null && !primaryExchange.isBlank()) {
      contract.exchange(primaryExchange);
    }
    return contract;
  }

  public static Contract usStock(String symbol) {
    return stock(Instrument.us(symbol), DEFAULT_US_PRIMARY_EXCHANGE);
  }
}
