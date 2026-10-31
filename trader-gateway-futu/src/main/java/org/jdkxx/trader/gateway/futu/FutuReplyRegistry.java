package org.jdkxx.trader.gateway.futu;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.gateway.GatewayException;
import org.jdkxx.trader.gateway.NotConnectedException;
import org.jdkxx.trader.gateway.RequestRejectedException;
import org.jdkxx.trader.gateway.RequestTimeoutException;
import org.jdkxx.trader.gateway.UnknownSymbolException;

/**
 * Serial number → pending reply (one table per channel).
 *
 * <p>Race between sending and registering: the SDK returns the seq first and we register
 * afterwards, so in rare cases the reply arrives before the registration. Such an early reply is
 * parked in the {@code early} table, and registration checks that table first. "Check parked +
 * register" and "check registered + park" are each done as a single step under the lock; otherwise
 * a reply landing between the two steps would be claimed by neither side and time out for nothing.
 *
 * <p><b>Serial numbers restart at 1 per connection</b> (javap confirmed that FTAPI_Conn's
 * nextPacketSN starts at 1 for every instance), so {@link #reset} must be called when a session
 * ends: parked old replies are discarded, otherwise they would be matched to the request with the
 * same serial number on the new connection. Futures are always completed on the dispatch thread;
 * the SDK callback thread never runs user code.
 */
final class FutuReplyRegistry {

  private final String channel;
  private final ScheduledExecutorService scheduler;
  private final Executor dispatch;
  private final Duration timeout;
  private final ConcurrentHashMap<Integer, Entry<?>> pending = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Integer, Object> early = new ConcurrentHashMap<>();

  /**
   * Session generation, incremented by {@link #reset} (guarded by {@code this}).
   *
   * <p>"Sent" and "registered in pending" are not under the same lock: after {@code send} returns
   * the serial number and before {@code pending.put}, the session may end, so the request
   * <b>escapes</b> reset's guarantee that every in-flight request fails, and is then registered in
   * the new session's pending table. Because serial numbers restart at 1 per connection, the new
   * connection soon uses the same serial number and overwrites the entry; the overwritten Entry's
   * timeout task then runs {@code pending.remove(seq, entry)}, the comparison fails, and the future
   * is <b>never completed</b>, so the caller hangs forever (found in the 2026-09-25 full-project
   * review). We therefore record the generation before sending and fail on the spot at registration
   * if it has changed.
   */
  private int generation;

  private static final class Entry<R> {
    final String what;
    final Class<R> type;
    final CompletableFuture<R> future;
    volatile ScheduledFuture<?> timeoutTask;

    Entry(String what, Class<R> type, CompletableFuture<R> future) {
      this.what = what;
      this.type = type;
      this.future = future;
    }
  }

  FutuReplyRegistry(
      String channel, ScheduledExecutorService scheduler, Executor dispatch, Duration timeout) {
    this.channel = channel;
    this.scheduler = scheduler;
    this.dispatch = dispatch;
    this.timeout = timeout;
  }

  /**
   * Sends one request. The returned future is completed on the dispatch thread with the typed
   * reply, a broker rejection, a timeout, or a disconnect.
   *
   * @param send performs the SDK call and returns the serial number (≤0 is treated as a send
   *     failure). It runs on the calling thread and may block on the rate limiter, which is why no
   *     lock is held around it
   */
  <R> CompletableFuture<R> call(String what, Class<R> type, IntSupplier send) {
    CompletableFuture<R> future = new CompletableFuture<>();
    Entry<R> entry = new Entry<>(what, type, future);
    int gen;
    synchronized (this) {
      gen = generation;
    }
    int seq;
    try {
      seq = send.getAsInt();
    } catch (RuntimeException e) {
      future.completeExceptionally(
          new GatewayException(Broker.FUTU, 0, what + " 发送失败：" + e.getMessage(), true, e));
      return future;
    }
    if (seq <= 0) {
      future.completeExceptionally(
          new GatewayException(
              Broker.FUTU, 0, what + " 发送失败（" + channel + "通道返回 seq=" + seq + "，连接可能未就绪）", true));
      return future;
    }
    Object earlyReply;
    synchronized (this) {
      if (generation != gen) {
        // The session ended between "sent" and "registered": this request has already missed
        // reset's failure notice, and registering it would only get it overwritten by the same
        // serial number in the new session and leave it hanging forever
        dispatch.execute(
            () ->
                future.completeExceptionally(
                    new NotConnectedException(Broker.FUTU, what + " 发出后连接就断了")));
        return future;
      }
      earlyReply = early.remove(seq);
      if (earlyReply == null) {
        pending.put(seq, entry);
      }
    }
    if (earlyReply != null) {
      finish(entry, earlyReply);
      return future;
    }
    // remove(seq, entry) only removes this exact entry. If the reply wins the race and arrives
    // before timeoutTask is assigned, the task is not cancelled, but when it fires the remove is a
    // harmless no-op
    entry.timeoutTask =
        scheduler.schedule(
            () -> {
              if (pending.remove(seq, entry)) {
                dispatch.execute(
                    () ->
                        future.completeExceptionally(
                            new RequestTimeoutException(Broker.FUTU, what)));
              }
            },
            timeout.toMillis(),
            TimeUnit.MILLISECONDS);
    return future;
  }

  /**
   * Called on the SDK callback thread: only moves the reply between the maps under the lock;
   * completion is handed to dispatch.
   */
  void onReply(int seq, Object response) {
    Entry<?> entry;
    synchronized (this) {
      entry = pending.remove(seq);
      if (entry == null) {
        early.put(seq, response);
      }
    }
    if (entry == null) {
      // A parked reply that nobody claims within the timeout (for example, its request has already
      // timed out) is dropped so that the early table cannot grow without bound
      scheduler.schedule(
          () -> early.remove(seq, response), timeout.toMillis(), TimeUnit.MILLISECONDS);
      return;
    }
    if (entry.timeoutTask != null) {
      entry.timeoutTask.cancel(false);
    }
    finish(entry, response);
  }

  /**
   * Classifies the reply on the dispatch thread. retType != 0 becomes a rejection; "unknown stock"
   * rejections become {@link UnknownSymbolException} so that callers can run batch-level recovery
   * (ARCHITECTURE §22).
   */
  private <R> void finish(Entry<R> entry, Object response) {
    dispatch.execute(
        () -> {
          try {
            FutuReplyStatus status = FutuReplyStatus.of(response);
            if (!status.ok()) {
              String message =
                  entry.what + " 失败：" + status.retMsg() + "（retType=" + status.retType() + "）";
              entry.future.completeExceptionally(
                  FutuUnknownSymbol.looksUnknown(status.retMsg())
                      ? new UnknownSymbolException(
                          Broker.FUTU,
                          status.code(),
                          message,
                          FutuUnknownSymbol.named(status.retMsg()))
                      : new RequestRejectedException(Broker.FUTU, status.code(), message));
              return;
            }
            entry.future.complete(entry.type.cast(response));
          } catch (RuntimeException e) {
            entry.future.completeExceptionally(e);
          }
        });
  }

  /**
   * Session ended: fail every in-flight request and discard parked replies (serial numbers restart
   * at 1 on the new connection).
   */
  void reset(Throwable error) {
    synchronized (this) {
      generation++;
      early.clear();
    }
    failAll(error);
  }

  void failAll(Throwable error) {
    for (Integer seq : pending.keySet()) {
      Entry<?> e = pending.remove(seq);
      if (e != null) {
        if (e.timeoutTask != null) {
          e.timeoutTask.cancel(false);
        }
        dispatch.execute(() -> e.future.completeExceptionally(error));
      }
    }
  }

  int pendingCount() {
    return pending.size();
  }
}
