package org.jdkxx.trader.gateway.ibkr;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.jdkxx.trader.gateway.RequestRejectedException;

/**
 * reqId to handler for standing subscriptions (since 3.0.2, used by the live account). {@link
 * IbkrRequestRegistry} only covers "request, collect, end"; this class covers "once subscribed,
 * keeps pushing". reqIds come from the same counter as the registry, so the two never collide.
 *
 * <p>Callbacks arrive on the pump thread and are only handed off here: every handler runs on the
 * (single-threaded) dispatch thread, in arrival order. That single thread is what lets the
 * subscribers ({@link IbkrLiveAccount}, {@link IbkrAccountSummaryFeed}, {@link IbkrPortfolioFeed})
 * keep their state without locks.
 */
final class IbkrSubscriptions {

  interface Handler {
    void item(Object item);

    default void end() {}

    default void error(RequestRejectedException e) {}
  }

  /**
   * The account-updates feed (reqAccountUpdates) has no reqId: IBKR allows one client to subscribe
   * to only one account at a time, and the callbacks carry no id either. A negative number that can
   * never collide with a real reqId is used as its channel id ({@link
   * IbkrRequestRegistry#isRequestId} is false for negatives).
   */
  static final int PORTFOLIO = -2;

  private final ConcurrentHashMap<Integer, Handler> handlers = new ConcurrentHashMap<>();
  private final Executor dispatch;

  IbkrSubscriptions(Executor dispatch) {
    this.dispatch = dispatch;
  }

  void open(int id, Handler handler) {
    handlers.put(id, handler);
  }

  void close(int id) {
    handlers.remove(id);
  }

  boolean has(int id) {
    return handlers.containsKey(id);
  }

  int size() {
    return handlers.size();
  }

  void item(int id, Object item) {
    Handler h = handlers.get(id);
    if (h != null) {
      run(() -> h.item(item));
    }
  }

  void end(int id) {
    Handler h = handlers.get(id);
    if (h != null) {
      run(h::end);
    }
  }

  /**
   * @return whether this id is a standing subscription (if so the error has been handed off and the
   *     caller need not treat it as a system message)
   */
  boolean error(int id, RequestRejectedException e) {
    Handler h = handlers.get(id);
    if (h == null) {
      return false;
    }
    run(() -> h.error(e));
    return true;
  }

  /**
   * Session ended: its subscriptions die with it and every handler is discarded (after
   * reconnecting, subscribers re-subscribe with new reqIds).
   */
  void clear() {
    handlers.clear();
  }

  private void run(Runnable r) {
    try {
      dispatch.execute(r);
    } catch (RejectedExecutionException e) {
      // shutting down
    }
  }
}
