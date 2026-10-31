package org.jdkxx.trader.gateway;

import org.jdkxx.trader.domain.Broker;

/**
 * Live account port (since 3.0.2, read-only): standing subscriptions to the funds summary, account
 * P&amp;L, positions, per-position P&amp;L and the latest market price of each held instrument,
 * pushed to a listener. Every value is the broker's raw value with no conversion of any kind (the
 * user wants it to match the IBKR app).
 *
 * <p>Kept separate from {@link AccountGateway}, which serves the daily snapshot, while this one is
 * opened on demand and closed when idle. IBKR caps account-summary subscriptions at 2 per client
 * (error 322, measured 2026-09-19), and the cap counts subscriptions <em>ever made</em> in the
 * connection: cancelling does not free a slot, only a reconnect does (measured 2026-09-23). So
 * since 3.0.9 the IBKR adapter keeps a single standing account-summary subscription per connection
 * cycle, shared by this port and the daily snapshot; going idle only detaches the listener and
 * never cancels the subscription.
 *
 * <p>Subscriptions die with the session on disconnect; unless {@link #stopLive()} was called, the
 * adapter re-subscribes automatically after reconnecting. {@code accountId} is the broker's raw
 * account number: neither implementations nor callers may put it into logs, exception messages or
 * API responses.
 */
public interface LiveAccountGateway {

  Broker broker();

  /**
   * Starts the subscriptions. Returns immediately if the same account is already subscribed;
   * switching accounts unsubscribes the old one first. When not connected, the intent is remembered
   * and the subscription is made once connected.
   */
  void startLive(String accountId, LiveAccountListener listener);

  /** Unsubscribes everything and forgets the intent. Does nothing when not subscribed. */
  void stopLive();

  /** Whether a subscription intent currently exists (it does not mean data has arrived). */
  boolean liveActive();
}
