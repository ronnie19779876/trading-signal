package org.jdkxx.trader.gateway.ibkr;

import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.gateway.RequestTimeoutException;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * reqId → 待完成请求。reqId 从 {@value #FIRST_ID} 起自增，与 orderId 的取值空间分开
 * （TWS 的 {@code error(id, …)} 里 id 既可能是 reqId 也可能是 orderId）。
 *
 * <p>Future 的完成一律交给 dispatch 线程执行，保证泵线程（EReader 消息处理）永远不跑用户代码。
 */
final class IbkrRequestRegistry {

    static final int FIRST_ID = 10_000_000;

    private final AtomicInteger ids = new AtomicInteger(FIRST_ID);
    private final ConcurrentHashMap<Integer, Entry<?>> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;
    private final Executor dispatch;
    private final Duration timeout;

    private record Entry<T>(String what, PendingRequest<T> request, CompletableFuture<T> future,
                            ScheduledFuture<?> timeoutTask, Runnable onTimeout) {
    }

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
     * @param onTimeout 超时后要执行的取消动作（例如 cancelMktData），可为 null
     */
    <T> CompletableFuture<T> open(int id, String what, PendingRequest<T> request, Runnable onTimeout) {
        CompletableFuture<T> future = new CompletableFuture<>();
        ScheduledFuture<?> t = scheduler.schedule(() -> {
            if (pending.remove(id) != null) {
                if (onTimeout != null) {
                    try {
                        onTimeout.run();
                    } catch (RuntimeException ignored) {
                        // 取消动作失败不影响超时结论
                    }
                }
                dispatch.execute(() -> future.completeExceptionally(new RequestTimeoutException(Broker.IBKR, what)));
            }
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);
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
        dispatch.execute(() -> {
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
