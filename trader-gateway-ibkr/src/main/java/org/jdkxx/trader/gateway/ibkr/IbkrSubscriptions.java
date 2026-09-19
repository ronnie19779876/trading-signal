package org.jdkxx.trader.gateway.ibkr;

import org.jdkxx.trader.gateway.RequestRejectedException;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 常驻订阅的 reqId → 处理器（3.0.2 起，实时账户用）。{@link IbkrRequestRegistry} 只管"请求 → 收齐 → 结束"，
 * 这里管"订上之后一直推"。reqId 与注册表共用同一个计数器，两边不会撞号。
 *
 * <p>回调在泵线程上到达，这里只转交：处理器一律在 dispatch 线程（单线程）上按到达顺序执行。
 */
final class IbkrSubscriptions {

    interface Handler {
        void item(Object item);

        default void end() {
        }

        default void error(RequestRejectedException e) {
        }
    }

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

    /** @return 这个 id 是否是常驻订阅（是则错误已转交，调用方不必再当系统消息处理） */
    boolean error(int id, RequestRejectedException e) {
        Handler h = handlers.get(id);
        if (h == null) {
            return false;
        }
        run(() -> h.error(e));
        return true;
    }

    /** 会话结束：订阅随之失效，处理器全部作废（重连后由订阅方用新 reqId 重订）。 */
    void clear() {
        handlers.clear();
    }

    private void run(Runnable r) {
        try {
            dispatch.execute(r);
        } catch (RejectedExecutionException e) {
            // 关停中
        }
    }
}
