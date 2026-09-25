package org.jdkxx.trader.gateway.futu;

import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.gateway.GatewayException;
import org.jdkxx.trader.gateway.NotConnectedException;
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
 * 序列号 → 待完成回复（每条通道一张表）。
 *
 * <p>发送与登记的竞争：SDK 先返回 seq、我们再登记；极端情况下回复可能先于登记到达，
 * 所以先到的回复暂存在 early 表里，登记时先查它。"查暂存 + 登记"与"查登记 + 暂存"各自在锁里做成一步，
 * 否则回复落在两步之间会谁都拿不到、白白超时。
 *
 * <p><b>序列号按连接从 1 重新数</b>（javap 核实 FTAPI_Conn 的 nextPacketSN 每个实例从 1 起），
 * 所以会话结束时要 {@link #reset}：暂存的旧回复作废，否则会配给新连接上同序列号的请求。
 * Future 的完成一律交给 dispatch 线程，SDK 回调线程不跑用户代码。
 */
final class FutuReplyRegistry {

    private final String channel;
    private final ScheduledExecutorService scheduler;
    private final Executor dispatch;
    private final Duration timeout;
    private final ConcurrentHashMap<Integer, Entry<?>> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Object> early = new ConcurrentHashMap<>();
    /**
     * 会话代数，{@link #reset} 时递增（由 this 保护）。
     *
     * <p>「发出去」与「登记进 pending」不在同一把锁里：{@code send} 返回序列号之后、
     * {@code pending.put} 之前，会话可能正好结束，于是这个请求<b>逃过</b> {@code reset} 的
     * 「在途请求全部失败」保证，然后被登记到新会话的 pending 里。而序列号按连接从 1 重新数，
     * 新连接很快会用掉同一个序列号并把它顶替掉——被顶替的那个 Entry 的超时任务执行
     * {@code pending.remove(seq, entry)} 比对失败，future <b>永远不会被完成</b>，调用方一直挂着
     * （2026-09-25 全项目审查发现）。
     * 记下发送前的代数，登记时发现代数变了就当场失败。
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
        int gen;
        synchronized (this) {
            gen = generation;
        }
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
        Object earlyReply;
        synchronized (this) {
            if (generation != gen) {
                // 会话在「发出去」与「登记」之间结束了：这一条已经错过 reset 的失败通知，
                // 登记进去只会被新会话的同序列号顶替掉，然后永远挂着
                dispatch.execute(() -> future.completeExceptionally(new NotConnectedException(Broker.FUTU,
                        what + " 发出后连接就断了")));
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
        entry.timeoutTask = scheduler.schedule(() -> {
            if (pending.remove(seq, entry)) {
                dispatch.execute(() -> future.completeExceptionally(new RequestTimeoutException(Broker.FUTU, what)));
            }
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);
        return future;
    }

    /** SDK 回调线程调用。 */
    void onReply(int seq, Object response) {
        Entry<?> entry;
        synchronized (this) {
            entry = pending.remove(seq);
            if (entry == null) {
                early.put(seq, response);
            }
        }
        if (entry == null) {
            scheduler.schedule(() -> early.remove(seq, response), timeout.toMillis(), TimeUnit.MILLISECONDS);
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

    /** 会话结束：在途请求全部失败，暂存的回复作废（新连接的序列号从 1 重新数）。 */
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
