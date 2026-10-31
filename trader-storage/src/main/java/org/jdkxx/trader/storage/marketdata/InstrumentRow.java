package org.jdkxx.trader.storage.marketdata;

import java.time.LocalDate;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.SecurityType;

public record InstrumentRow(
    long id,
    Market market,
    String symbol,
    String name,
    String nameCn,
    SecurityType type,
    Integer lotSize,
    LocalDate listDate,
    boolean delisted,
    String exchange,
    Long brokerId,
    String resolveStatus) {

  public Instrument instrument() {
    return new Instrument(market, symbol);
  }
}
