package org.jdkxx.trader.gateway.ibkr;

import java.util.ArrayList;
import java.util.List;

/**
 * How a TWS request that carries a reqId accumulates its result from callbacks: a single value or a
 * list.
 *
 * <p>{@link #accept} runs on the pump thread (one call per data callback), {@link #result} runs on
 * the dispatch thread once the end callback arrives, so both implementations must publish safely
 * across threads.
 */
abstract class PendingRequest<T> {

  abstract void accept(Object item);

  abstract T result();

  static final class Single<T> extends PendingRequest<T> {
    private final Class<T> type;
    private volatile T value;

    Single(Class<T> type) {
      this.type = type;
    }

    @Override
    void accept(Object item) {
      value = type.cast(item);
    }

    @Override
    T result() {
      return value;
    }
  }

  static final class Many<E> extends PendingRequest<List<E>> {
    private final Class<E> type;
    private final List<E> items = new ArrayList<>();

    Many(Class<E> type) {
      this.type = type;
    }

    @Override
    synchronized void accept(Object item) {
      items.add(type.cast(item));
    }

    @Override
    synchronized List<E> result() {
      return List.copyOf(items);
    }
  }
}
