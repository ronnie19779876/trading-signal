package org.jdkxx.trader.gateway.ibkr;

import org.jdkxx.trader.gateway.RequestTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IbkrRequestRegistryTest {

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    void 累积多条回调并在结束回调时完成() throws Exception {
        IbkrRequestRegistry r = new IbkrRequestRegistry(scheduler, Runnable::run, Duration.ofSeconds(5));
        int id = r.nextId();
        assertThat(IbkrRequestRegistry.isRequestId(id)).isTrue();
        CompletableFuture<List<String>> f = r.open(id, "t", new PendingRequest.Many<>(String.class), null);

        r.item(id, "a");
        r.item(id, "b");
        assertThat(f).isNotDone();
        r.complete(id);

        assertThat(f.get(1, TimeUnit.SECONDS)).containsExactly("a", "b");
        assertThat(r.isPending(id)).isFalse();
    }

    @Test
    void 超时后异常完成并执行取消动作() {
        IbkrRequestRegistry r = new IbkrRequestRegistry(scheduler, Runnable::run, Duration.ofMillis(50));
        AtomicBoolean cancelled = new AtomicBoolean();
        int id = r.nextId();
        CompletableFuture<String> f = r.open(id, "慢请求", new PendingRequest.Single<>(String.class), () -> cancelled.set(true));

        assertThatThrownBy(() -> f.get(2, TimeUnit.SECONDS)).hasCauseInstanceOf(RequestTimeoutException.class);
        assertThat(cancelled).isTrue();
        assertThat(r.isPending(id)).isFalse();
    }

    @Test
    void 类型不匹配的回调让请求失败而不是抛到泵线程() {
        IbkrRequestRegistry r = new IbkrRequestRegistry(scheduler, Runnable::run, Duration.ofSeconds(5));
        int id = r.nextId();
        CompletableFuture<List<String>> f = r.open(id, "t", new PendingRequest.Many<>(String.class), null);

        r.item(id, 42);

        assertThat(f).isCompletedExceptionally();
    }

    @Test
    void failAll让所有在途请求失败() {
        IbkrRequestRegistry r = new IbkrRequestRegistry(scheduler, Runnable::run, Duration.ofSeconds(5));
        CompletableFuture<String> a = r.open(r.nextId(), "a", new PendingRequest.Single<>(String.class), null);
        CompletableFuture<String> b = r.open(r.nextId(), "b", new PendingRequest.Single<>(String.class), null);

        r.failAll(new IllegalStateException("断开"));

        assertThat(a).isCompletedExceptionally();
        assertThat(b).isCompletedExceptionally();
        assertThat(r.pendingCount()).isZero();
    }
}
