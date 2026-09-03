package org.jdkxx.trader.gateway.support;

import java.util.concurrent.CompletableFuture;

/**
 * 适配器向 {@link ConnectionSupervisor} 提供的传输层。三个方法都不得阻塞调用线程太久：
 * 建连里的同步握手请放到自己的线程上，用 Future 回报。
 */
public interface Transport {

    /** 建连并等待"就绪"（盈透：nextValidId；富途：onInitConnect errCode==0）。 */
    CompletableFuture<Void> open();

    /** 幂等关闭。 */
    void close();

    /** 心跳探测，true 表示对端有应答。 */
    CompletableFuture<Boolean> probe();
}
