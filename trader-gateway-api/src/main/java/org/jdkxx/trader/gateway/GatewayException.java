package org.jdkxx.trader.gateway;

import org.jdkxx.trader.domain.Broker;

/**
 * Gateway-layer exception. {@code code} is the broker-side error code (IBKR error code, Futu
 * retType or errCode), or 0 when there is none. {@code retryable} tells the connection supervisor
 * whether a failed connect should be retried (false means a configuration-type problem that
 * retrying will not fix).
 */
public class GatewayException extends RuntimeException {

  private final Broker broker;
  private final int code;
  private final boolean retryable;

  public GatewayException(Broker broker, int code, String message, boolean retryable) {
    this(broker, code, message, retryable, null);
  }

  public GatewayException(
      Broker broker, int code, String message, boolean retryable, Throwable cause) {
    super("[" + broker + (code != 0 ? " " + code : "") + "] " + message, cause);
    this.broker = broker;
    this.code = code;
    this.retryable = retryable;
  }

  public Broker broker() {
    return broker;
  }

  public int code() {
    return code;
  }

  public boolean retryable() {
    return retryable;
  }
}
