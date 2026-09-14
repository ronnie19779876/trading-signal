package org.jdkxx.trader.gateway.support;

import java.util.concurrent.CompletableFuture;

/**
 * 适配器向 {@link ConnectionSupervisor} 提供的传输层。方法都不得阻塞调用线程太久：
 * 建连里的同步握手请放到自己的线程上，用 Future 回报。状态机在自己的锁里调 open() / close()，
 * 这两个方法<b>不得等待 SDK 的锁</b>，否则 SDK 卡住时状态查询、断开、关停都会跟着卡死。
 */
public interface Transport {

    /** 建连并等待"就绪"（盈透：nextValidId；富途：onInitConnect errCode==0）。 */
    CompletableFuture<Void> open();

    /** 幂等关闭。 */
    void close();

    /** 心跳探测，true 表示对端有应答。 */
    CompletableFuture<Boolean> probe();

    /** 此刻连接是否仍可用。状态机在把"就绪"落成 CONNECTED 之前核对一次（在锁外调用）。 */
    default boolean isOpen() {
        return true;
    }
}
