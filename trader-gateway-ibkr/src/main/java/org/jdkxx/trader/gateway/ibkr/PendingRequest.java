package org.jdkxx.trader.gateway.ibkr;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个带 reqId 的 TWS 请求在回调里累积结果的方式：单值或列表。
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
