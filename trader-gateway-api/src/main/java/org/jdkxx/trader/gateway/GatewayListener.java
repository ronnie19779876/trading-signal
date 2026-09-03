package org.jdkxx.trader.gateway;

import org.jdkxx.trader.domain.Broker;

/**
 * 网关连接事件。回调在网关的调度线程上执行，<b>不得阻塞</b>（落库、网络等交给自己的线程）。
 */
public interface GatewayListener {

    /** @param reconnected true 表示这是断线后的重连（第 2 期的订阅恢复挂在这里） */
    default void onConnected(Broker broker, boolean reconnected) {
    }

    default void onDisconnected(Broker broker, String reason) {
    }

    default void onError(Broker broker, GatewayException error) {
    }
}
