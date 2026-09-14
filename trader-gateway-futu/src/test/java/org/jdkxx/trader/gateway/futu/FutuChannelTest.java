package org.jdkxx.trader.gateway.futu;

import org.jdkxx.trader.common.ratelimit.RateLimiter;
import org.jdkxx.trader.gateway.NotConnectedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;

class FutuChannelTest {

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService dispatch = Executors.newSingleThreadExecutor(r -> new Thread(r, FutuChannel.DISPATCH_THREAD));

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
        dispatch.shutdownNow();
    }

    private FutuChannel channel() {
        FutuProperties d = FutuProperties.disabled();
        FutuProperties props = new FutuProperties(true, true, "h", 1, "x", false, null, d.connectTimeout(), d.replyTimeout(),
                d.healthInterval(), d.reconnect(), Map.of());
        RateLimiter none = new RateLimiter() {
            @Override
            public void acquire() {
            }

            @Override
            public boolean tryAcquire() {
                return true;
            }
        };
        return new FutuChannel(FutuChannel.Kind.QOT, props, new FutuReplyRegistry("t", scheduler, Runnable::run, Duration.ofSeconds(1)), n -> none);
    }

    @Test
    void 不得在回复线程上发请求() throws Exception {
        // 发送前要过限流器、可能睡几十秒；回复线程同时完成所有回复与报价推送，睡在上面心跳会超时断线
        FutuChannel channel = channel();

        CompletableFuture<Object> onDispatch = dispatch.submit(() -> channel.qotCall("get-kl", "getKL AAPL", Object.class, c -> 1)).get();

        assertThat(onDispatch).failsWithin(Duration.ofSeconds(1))
                .withThrowableOfType(ExecutionException.class)
                .withCauseInstanceOf(IllegalStateException.class)
                .withMessageContaining(FutuChannel.DISPATCH_THREAD);

        assertThat(channel.qotCall("get-kl", "getKL AAPL", Object.class, c -> 1)).as("其它线程照常走到连接检查")
                .failsWithin(Duration.ofSeconds(1))
                .withThrowableOfType(ExecutionException.class)
                .withCauseInstanceOf(NotConnectedException.class);
    }
}
