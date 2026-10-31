package org.jdkxx.trader.gateway;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.InstrumentInfo;

/**
 * Reference data (contract details) port. Only IBKR implements it in this phase. An unknown
 * instrument yields an empty list rather than an exception.
 */
public interface ReferenceDataGateway {

  CompletableFuture<List<InstrumentInfo>> lookup(Instrument instrument);
}
