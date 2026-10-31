package org.jdkxx.trader.gateway.support;

import java.util.concurrent.CompletableFuture;

/**
 * Transport layer that an adapter provides to {@link ConnectionSupervisor}. No method may block the
 * calling thread for long: run the synchronous handshake of a connect on your own thread and report
 * back through the future. The state machine calls open() / close() while holding its own lock, so
 * these two methods <b>must never wait for an SDK lock</b>; otherwise a stuck SDK would freeze
 * status queries, disconnect and shutdown along with it (the IBKR {@code EClientSocket} methods are
 * synchronized and an unanswered handshake holds that lock, the pre-2.0.2 hang).
 */
public interface Transport {

  /** Connects and waits for "ready" (IBKR: nextValidId; Futu: onInitConnect with errCode==0). */
  CompletableFuture<Void> open();

  /** Idempotent close. */
  void close();

  /** Heartbeat probe; true means the peer answered. */
  CompletableFuture<Boolean> probe();

  /**
   * Whether the connection is still usable right now. The state machine checks this once before
   * turning "ready" into CONNECTED (called outside its lock, because it may touch SDK locks).
   */
  default boolean isOpen() {
    return true;
  }
}
