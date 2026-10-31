package org.jdkxx.trader.core.marketdata;

import java.util.NoSuchElementException;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.storage.marketdata.InstrumentRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;

/**
 * Small facade for instrument lookup: an unknown symbol or id throws a {@link
 * NoSuchElementException}, i.e. an exception with 404 (not found) semantics.
 */
public class InstrumentDirectory {

  private final InstrumentRepository instruments;

  public InstrumentDirectory(InstrumentRepository instruments) {
    this.instruments = instruments;
  }

  public InstrumentRow require(String symbol) {
    Instrument i = Instrument.us(symbol);
    return instruments
        .find(i)
        .orElseThrow(
            () ->
                new NoSuchElementException(
                    "标的 " + i.symbol() + " 不在库里：先同步成分股（POST /api/universe/sync）或导入 CSV"));
  }

  public InstrumentRow require(long id) {
    return instruments
        .findById(id)
        .orElseThrow(() -> new NoSuchElementException("标的 id=" + id + " 不存在"));
  }
}
