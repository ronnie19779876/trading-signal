package org.jdkxx.trader.gateway.futu;

import com.futu.openapi.FTAPI_Conn_Qot;
import java.util.concurrent.CompletableFuture;
import java.util.function.ToIntFunction;

/**
 * Minimal abstraction of a market-data channel call: pass the rate limiter → call the SDK → wait
 * for the reply by serial number. It keeps the request-building code (FutuMarketData) independent
 * of the connection object, which makes it testable.
 */
@FunctionalInterface
public interface QotCalls {

  <R> CompletableFuture<R> call(
      String limitName, String what, Class<R> type, ToIntFunction<FTAPI_Conn_Qot> send);
}
