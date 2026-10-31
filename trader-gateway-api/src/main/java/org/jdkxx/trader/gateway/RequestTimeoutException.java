package org.jdkxx.trader.gateway;

import org.jdkxx.trader.domain.Broker;

/** A request (or the connect handshake / heartbeat) got no answer in time; retryable. */
public class RequestTimeoutException extends GatewayException {

  public RequestTimeoutException(Broker broker, String what) {
    super(broker, 0, what + " 超时", true);
  }
}
