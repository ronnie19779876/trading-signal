package org.jdkxx.trader.gateway;

/**
 * 网关连接状态。DISABLED 表示配置里没有启用这家券商，不算故障。
 */
public enum GatewayState {
    DISABLED,
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}
