package org.jdkxx.trader.gateway.futu;

import com.futu.openapi.FTAPI_Conn_Qot;

import java.util.concurrent.CompletableFuture;
import java.util.function.ToIntFunction;

/**
 * 行情通道调用的最小抽象：过限流器 → 调 SDK → 按序列号等回复。让请求构造代码（FutuMarketData）不依赖连接对象，便于测试。
 */
@FunctionalInterface
public interface QotCalls {

    <R> CompletableFuture<R> call(String limitName, String what, Class<R> type, ToIntFunction<FTAPI_Conn_Qot> send);
}
