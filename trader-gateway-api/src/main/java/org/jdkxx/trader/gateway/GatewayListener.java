package org.jdkxx.trader.gateway;

import org.jdkxx.trader.domain.Broker;

/**
 * Gateway connection events. Callbacks run on the gateway's scheduler thread and <b>must not
 * block</b> (hand database writes, network calls and the like to your own thread); a blocked
 * scheduler delays heartbeats and gets the connection declared dead.
 *
 * <p>Each notification is submitted to the scheduler as its own task. The scheduler is a small
 * thread pool, so successive notifications are not guaranteed to be delivered strictly in order or
 * on the same thread; listeners must be thread-safe.
 */
public interface GatewayListener {

  /**
   * @param reconnected true when this is a reconnect after a disconnect, or a broker-side data loss
   *     on a live connection (IBKR 1101); subscription recovery (phase 2) hooks in here
   */
  default void onConnected(Broker broker, boolean reconnected) {}

  default void onDisconnected(Broker broker, String reason) {}

  default void onError(Broker broker, GatewayException error) {}
}
