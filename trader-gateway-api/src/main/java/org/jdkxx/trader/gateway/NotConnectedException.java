package org.jdkxx.trader.gateway;

import org.jdkxx.trader.domain.Broker;

/** The gateway is not connected; retryable, since the connection supervisor keeps reconnecting. */
public class NotConnectedException extends GatewayException {

  public NotConnectedException(Broker broker, String detail) {
    super(broker, 0, "网关未连接：" + detail, true);
  }
}
