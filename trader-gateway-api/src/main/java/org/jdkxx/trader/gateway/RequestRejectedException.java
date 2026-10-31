package org.jdkxx.trader.gateway;

import org.jdkxx.trader.domain.Broker;

/** The broker explicitly rejected the request (with an error code); generally not retryable. */
public class RequestRejectedException extends GatewayException {

  public RequestRejectedException(Broker broker, int code, String message) {
    super(broker, code, message, false);
  }
}
