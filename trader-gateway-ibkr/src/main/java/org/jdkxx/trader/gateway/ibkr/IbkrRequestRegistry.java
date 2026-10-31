package org.jdkxx.trader.gateway.ibkr;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.gateway.RequestTimeoutException;

/**
 * reqId to pending request. reqIds increment from {@value #FIRST_ID}, kept apart from the orderId
 * value space (in TWS's {@code error(id, …)} the id may be either a reqId or an orderId, and {@link
 * #isRequestId} tells them apart).
 *
 * <p>Futures are always completed on the dispatch thread, so the pump thread (EReader message
 * processing) never runs user code: a {@code thenApply} attached by a caller would otherwise
 * execute on the pump and stall every other callback.
 *
 * <p>Every entry leaves the map exactly once, through whichever comes first of {@link #complete},
 * {@link #fail} or its timeout task ({@code pending.remove} is the arbiter), so a future is
 * completed at most once and every request ends, at the latest, at its timeout. The timeout task
 * (including {@code onTimeout}) runs on the scheduler thread.
 */
final class IbkrRequestRegistry {

  static final int FIRST_ID = 10_000_000;

  private final AtomicInteger ids = new AtomicInteger(FIRST_ID);
  private final ConcurrentHashMap<Integer, Entry<?>> pending = new ConcurrentHashMap<>();
  private final ScheduledExecutorService scheduler;
  private final Executor dispatch;
  private final Duration timeout;

  private record Entry<T>(
      String what,
      PendingRequest<T> request,
      CompletableFuture<T> future,
      ScheduledFuture<?> timeoutTask,
      Runnable onTimeout) {}

  IbkrRequestRegistry(ScheduledExecutorService scheduler, Executor dispatch, Duration timeout) {
    this.scheduler = scheduler;
    this.dispatch = dispatch;
    this.timeout = timeout;
  }

  int nextId() {
    return ids.getAndIncrement();
  }

  static boolean isRequestId(int id) {
    return id >= FIRST_ID;
  }

  /**
   * @param onTimeout cancel action to run after a timeout (for example cancelMktData); may be null
   */
  <T> CompletableFuture<T> open(
      int id, String what, PendingRequest<T> request, Runnable onTimeout) {
    return open(id, what, request, onTimeout, timeout);
  }

  /**
   * Same as above, but with an explicit timeout (historical data, many years of daily bars at once,
   * is slower than an ordinary request).
   */
  <T> CompletableFuture<T> open(
      int id, String what, PendingRequest<T> request, Runnable onTimeout, Duration timeout) {
    CompletableFuture<T> future = new CompletableFuture<>();
    ScheduledFuture<?> t =
        scheduler.schedule(
            () -> {
              if (pending.remove(id) != null) {
                if (onTimeout != null) {
                  try {
                    onTimeout.run();
                  } catch (RuntimeException ignored) {
                    // a failing cancel action does not change the timeout verdict
                  }
                }
                dispatch.execute(
                    () ->
                        future.completeExceptionally(
                            new RequestTimeoutException(Broker.IBKR, what)));
              }
            },
            timeout.toMillis(),
            TimeUnit.MILLISECONDS);
    pending.put(id, new Entry<>(what, request, future, t, onTimeout));
    return future;
  }

  boolean isPending(int id) {
    return pending.containsKey(id);
  }

  int pendingCount() {
    return pending.size();
  }

  void item(int id, Object item) {
    Entry<?> e = pending.get(id);
    if (e == null) {
      return;
    }
    try {
      e.request().accept(item);
    } catch (RuntimeException ex) {
      fail(id, ex);
    }
  }

  void complete(int id) {
    Entry<?> e = pending.remove(id);
    if (e == null) {
      return;
    }
    e.timeoutTask().cancel(false);
    completeEntry(e);
  }

  private <T> void completeEntry(Entry<T> e) {
    dispatch.execute(
        () -> {
          try {
            e.future().complete(e.request().result());
          } catch (RuntimeException ex) {
            e.future().completeExceptionally(ex);
          }
        });
  }

  void fail(int id, Throwable error) {
    Entry<?> e = pending.remove(id);
    if (e == null) {
      return;
    }
    e.timeoutTask().cancel(false);
    dispatch.execute(() -> e.future().completeExceptionally(error));
  }

  void failAll(Throwable error) {
    for (Integer id : pending.keySet()) {
      fail(id, error);
    }
  }
}
