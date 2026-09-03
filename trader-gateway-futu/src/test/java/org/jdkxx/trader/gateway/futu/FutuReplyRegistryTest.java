package org.jdkxx.trader.gateway.futu;

import com.futu.openapi.pb.GetGlobalState;
import org.jdkxx.trader.gateway.RequestRejectedException;
import org.jdkxx.trader.gateway.RequestTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FutuReplyRegistryTest {

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    private static GetGlobalState.Response response(int retType, String msg) {
        return GetGlobalState.Response.newBuilder().setRetType(retType).setRetMsg(msg)
                .setS2C(GetGlobalState.S2C.newBuilder().setMarketHK(1).setMarketUS(1).setMarketSH(1).setMarketSZ(1)
                        .setMarketHKFuture(1).setQotLogined(true).setTrdLogined(true).setServerVer(1).setServerBuildNo(1)
                        .setTime(1).setLocalTime(1))
                .build();
    }

    @Test
    void 按序列号完成并解析成功状态() throws Exception {
        FutuReplyRegistry r = new FutuReplyRegistry("t", scheduler, Runnable::run, Duration.ofSeconds(5));
        CompletableFuture<GetGlobalState.Response> f = r.call("x", GetGlobalState.Response.class, () -> 7);
        r.onReply(7, response(0, ""));
        assertThat(f.get(1, TimeUnit.SECONDS).getS2C().getQotLogined()).isTrue();
        assertThat(r.pendingCount()).isZero();
    }

    @Test
    void 先到的回复不会丢() throws Exception {
        FutuReplyRegistry r = new FutuReplyRegistry("t", scheduler, Runnable::run, Duration.ofSeconds(5));
        r.onReply(9, response(0, ""));
        CompletableFuture<GetGlobalState.Response> f = r.call("x", GetGlobalState.Response.class, () -> 9);
        assertThat(f.get(1, TimeUnit.SECONDS)).isNotNull();
    }

    @Test
    void 失败状态映射为拒绝异常() {
        FutuReplyRegistry r = new FutuReplyRegistry("t", scheduler, Runnable::run, Duration.ofSeconds(5));
        CompletableFuture<GetGlobalState.Response> f = r.call("x", GetGlobalState.Response.class, () -> 3);
        r.onReply(3, response(-1, "no permission"));
        assertThatThrownBy(f::join).hasCauseInstanceOf(RequestRejectedException.class).hasMessageContaining("no permission");
    }

    @Test
    void 发送失败与超时() {
        FutuReplyRegistry r = new FutuReplyRegistry("t", scheduler, Runnable::run, Duration.ofMillis(50));
        assertThat(r.call("x", GetGlobalState.Response.class, () -> 0)).isCompletedExceptionally();
        CompletableFuture<GetGlobalState.Response> slow = r.call("slow", GetGlobalState.Response.class, () -> 5);
        assertThatThrownBy(() -> slow.get(2, TimeUnit.SECONDS)).hasCauseInstanceOf(RequestTimeoutException.class);
    }
}
