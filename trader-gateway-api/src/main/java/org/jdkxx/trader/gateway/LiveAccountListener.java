package org.jdkxx.trader.gateway;

import java.util.List;
import org.jdkxx.trader.domain.AccountPnl;
import org.jdkxx.trader.domain.AccountSummary;
import org.jdkxx.trader.domain.Position;
import org.jdkxx.trader.domain.PositionPnl;
import org.jdkxx.trader.domain.PositionPrice;

/**
 * Live account pushes. Callbacks run on the gateway's dispatch thread and <b>must not block</b>;
 * callbacks of one subscription are executed serially in arrival order.
 */
public interface LiveAccountListener {

  /**
   * Funds summary: once when the first batch is complete, then roughly every 3 minutes from the
   * broker (measured).
   */
  void onSummary(AccountSummary summary);

  /** Account P&amp;L: pushed on change, roughly every 5 seconds (measured). */
  void onPnl(AccountPnl pnl);

  /**
   * All positions: once when the first batch is complete, then the full list again on every change
   * (a fill). Zero-quantity entries are already removed.
   */
  void onPositions(List<Position> positions);

  /** P&amp;L and market value of a single position. */
  void onPositionPnl(PositionPnl pnl);

  /** Latest price of a single held instrument (IBKR market data). */
  void onPositionPrice(PositionPrice price);

  /**
   * A subscription was rejected by the broker (e.g. the account summary exceeding its concurrency
   * limit, 322). The subscription itself is not retried; the caller decides.
   */
  default void onLiveError(String what, GatewayException error) {}
}
