package org.jdkxx.trader.gateway.ibkr.mapper;

import com.ib.client.ContractDetails;
import java.math.BigDecimal;
import java.time.ZoneId;
import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.InstrumentInfo;

/**
 * TWS contract details to domain reference data. {@code stockType} (COMMON / ADR / REIT / NY REG
 * SHRS) is passed through: it is the authority for security type, since Futu files some REITs as
 * common stock or Trust.
 */
public final class IbkrInstruments {

  private IbkrInstruments() {}

  public static InstrumentInfo toInfo(Instrument instrument, ContractDetails cd) {
    return new InstrumentInfo(
        instrument,
        Broker.IBKR,
        Integer.toString(cd.conid()),
        cd.longName(),
        cd.contract() == null ? null : cd.contract().primaryExch(),
        cd.contract() == null ? null : cd.contract().currency(),
        cd.minTick() > 0 ? BigDecimal.valueOf(cd.minTick()).stripTrailingZeros() : null,
        zone(cd.timeZoneId()),
        cd.tradingHours(),
        cd.liquidHours(),
        cd.stockType() == null || cd.stockType().isBlank() ? null : cd.stockType());
  }

  static ZoneId zone(String id) {
    if (id == null || id.isBlank()) {
      return null;
    }
    try {
      return ZoneId.of(id.trim(), ZoneId.SHORT_IDS);
    } catch (RuntimeException e) {
      return null;
    }
  }
}
