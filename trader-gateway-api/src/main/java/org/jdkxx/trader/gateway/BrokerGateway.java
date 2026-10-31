package org.jdkxx.trader.gateway;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.jdkxx.trader.domain.AccountRef;
import org.jdkxx.trader.domain.Broker;

/**
 * Common port of a broker gateway: identity, lifecycle, status and accounts. Ports for market data,
 * reference data, trading and so on are added phase by phase.
 */
public interface BrokerGateway {

  Broker broker();

  /**
   * Whether the broker is enabled in configuration. When disabled, connect() completes at once and
   * the state stays DISABLED.
   */
  boolean enabled();

  /** Whether an enabled gateway connects automatically when the application starts. */
  boolean autoConnect();

  GatewayStatus status();

  /**
   * Idempotent. The returned future completes the next time the connection becomes ready; a failed
   * connection attempt does not throw but enters reconnection (so the future may stay pending for a
   * long time), and disconnect() completes it exceptionally.
   */
  CompletableFuture<Void> connect();

  /** Idempotent and synchronous: disconnects and stops reconnecting. */
  void disconnect();

  /**
   * IBKR: managed accounts; Futu: trading accounts. Completes exceptionally (NotConnectedException)
   * when not connected.
   */
  CompletableFuture<List<AccountRef>> accounts();

  void addListener(GatewayListener listener);
}
