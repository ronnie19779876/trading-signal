package org.jdkxx.trader.gateway.futu;

import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.gateway.GatewayException;
import org.jdkxx.trader.gateway.RequestRejectedException;
import org.jdkxx.trader.gateway.RequestTimeoutException;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

/**
 * 序列号 → 待完成回复（每条连接一张表；序列号按连接独立递增）。
 *
 * <p>发送与登记的竞争：SDK 先返回 seq、我们再登记；极端情况下回复可能先于登记到达，
 * 所以先到的回复暂存在 early 表里，登记时先查它。
 * Future 的完成一律交给 dispatch 线程，SDK 回调线程不跑用户代码。
 */
final class FutuReplyRegistry {

    private final String channel;
    private final ScheduledExecutorService scheduler;
    private final Executor dispatch;
    private final Duration timeout;
    private final ConcurrentHashMap<Integer, Entry<?>> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Object> early = new ConcurrentHashMap<>();

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

    FutuReplyRegistry(String channel, ScheduledExecutorService scheduler, Executor dispatch, Duration timeout) {
        this.channel = channel;
        this.scheduler = scheduler;
        this.dispatch = dispatch;
        this.timeout = timeout;
    }

    /**
     * @param send 执行 SDK 调用并返回序列号（≤0 视为发送失败）
     */
    <R> CompletableFuture<R> call(String what, Class<R> type, IntSupplier send) {
        CompletableFuture<R> future = new CompletableFuture<>();
        Entry<R> entry = new Entry<>(what, type, future);
        int seq;
        try {
            seq = send.getAsInt();
        } catch (RuntimeException e) {
            future.completeExceptionally(new GatewayException(Broker.FUTU, 0, what + " 发送失败：" + e.getMessage(), true, e));
            return future;
        }
        if (seq <= 0) {
            future.completeExceptionally(new GatewayException(Broker.FUTU, 0,
                    what + " 发送失败（" + channel + "通道返回 seq=" + seq + "，连接可能未就绪）", true));
            return future;
        }
        Object earlyReply = early.remove(seq);
        if (earlyReply != null) {
            finish(entry, earlyReply);
            return future;
        }
        entry.timeoutTask = scheduler.schedule(() -> {
            if (pending.remove(seq) == entry) {
                dispatch.execute(() -> future.completeExceptionally(new RequestTimeoutException(Broker.FUTU, what)));
            }
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);
        pending.put(seq, entry);
        return future;
    }

    /** SDK 回调线程调用。 */
    void onReply(int seq, Object response) {
        Entry<?> entry = pending.remove(seq);
        if (entry == null) {
            early.put(seq, response);
            scheduler.schedule(() -> early.remove(seq), timeout.toMillis(), TimeUnit.MILLISECONDS);
            return;
        }
        if (entry.timeoutTask != null) {
            entry.timeoutTask.cancel(false);
        }
        finish(entry, response);
    }

    private <R> void finish(Entry<R> entry, Object response) {
        dispatch.execute(() -> {
            try {
                FutuReplyStatus status = FutuReplyStatus.of(response);
                if (!status.ok()) {
                    entry.future.completeExceptionally(new RequestRejectedException(Broker.FUTU, status.code(),
                            entry.what + " 失败：" + status.retMsg() + "（retType=" + status.retType() + "）"));
                    return;
                }
                entry.future.complete(entry.type.cast(response));
            } catch (RuntimeException e) {
                entry.future.completeExceptionally(e);
            }
        });
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
