package org.jdkxx.trader.gateway;

/**
 * 网关连接状态。DISABLED 表示配置里没有启用这家券商，不算故障；
 * RECONNECTING 表示曾经连上（或首次连接失败后）正在按退避重试；ERROR 只用于不可重试的情况。
 */
public enum GatewayState {
    DISABLED,
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ERROR
}
