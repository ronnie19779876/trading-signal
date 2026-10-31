package org.jdkxx.trader.gateway;

/**
 * Gateway connection state. DISABLED means the broker is not enabled in configuration, which is not
 * a fault; RECONNECTING means the gateway was connected before (or the first connection attempt
 * failed) and is retrying with backoff; ERROR is used only for non-retryable situations (and when
 * the reconnect attempts are exhausted).
 */
public enum GatewayState {
  DISABLED,
  DISCONNECTED,
  CONNECTING,
  CONNECTED,
  RECONNECTING,
  ERROR
}
