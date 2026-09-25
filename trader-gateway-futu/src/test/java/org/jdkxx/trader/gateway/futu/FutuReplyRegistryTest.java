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

    /**
     * 会话在「发出去」与「登记」之间结束：这一条必须当场失败。
     *
     * <p>3.1.1 前它会逃过 reset 的「在途请求全部失败」并被登记到新会话的 pending 里，
     * 而序列号按连接从 1 重新数，新连接很快用掉同一个序列号把它顶替掉——被顶替的那个
     * Entry 的超时任务 {@code pending.remove(seq, entry)} 比对失败，future <b>永远不会完成</b>，
     * 调用方一直挂着（2026-09-25 全项目审查发现）。
     */
    @Test
    void 发出后会话就结束_当场失败而不是永远挂着() throws Exception {
        FutuReplyRegistry r = new FutuReplyRegistry("t", scheduler, Runnable::run, Duration.ofSeconds(5));

        // send 里模拟「刚发出去，连接就断了」
        CompletableFuture<GetGlobalState.Response> f = r.call("x", GetGlobalState.Response.class, () -> {
            r.reset(new IllegalStateException("连接断了"));
            return 5;
        });

        assertThat(f).isCompletedExceptionally();
        assertThatThrownBy(() -> f.get(1, TimeUnit.SECONDS)).hasRootCauseInstanceOf(
                org.jdkxx.trader.gateway.NotConnectedException.class);
        assertThat(r.pendingCount()).as("不能留在 pending 里等着被新会话的同序列号顶替").isZero();
    }

    /** 顶替的后果：新会话用掉同一个序列号，旧的那条再也完成不了。这条钉住「不会被顶替」。 */
    @Test
    void 新会话复用同序列号时_旧请求已经不在pending里() throws Exception {
        FutuReplyRegistry r = new FutuReplyRegistry("t", scheduler, Runnable::run, Duration.ofSeconds(5));
        CompletableFuture<GetGlobalState.Response> stale = r.call("旧", GetGlobalState.Response.class, () -> {
            r.reset(new IllegalStateException("连接断了"));
            return 1;
        });
        assertThat(stale).isCompletedExceptionally();

        CompletableFuture<GetGlobalState.Response> fresh = r.call("新", GetGlobalState.Response.class, () -> 1);
        r.onReply(1, response(0, ""));

        assertThat(fresh.get(1, TimeUnit.SECONDS)).as("新会话的同序列号请求照常完成").isNotNull();
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
    void 会话结束后暂存的旧回复作废_新连接同序列号拿不到() {
        // 每个 FTAPI_Conn 的序列号从 1 起：旧连接超时后迟到的回复，不能配给新连接上同序列号的请求（2.0.2 前会）
        FutuReplyRegistry r = new FutuReplyRegistry("t", scheduler, Runnable::run, Duration.ofMillis(100));
        r.onReply(1, response(0, "旧连接迟到的回复"));

        r.reset(new IllegalStateException("会话结束"));
        CompletableFuture<GetGlobalState.Response> f = r.call("x", GetGlobalState.Response.class, () -> 1);

        assertThatThrownBy(() -> f.get(2, TimeUnit.SECONDS)).hasCauseInstanceOf(RequestTimeoutException.class);
    }

    @Test
    void 会话结束让在途请求失败() {
        FutuReplyRegistry r = new FutuReplyRegistry("t", scheduler, Runnable::run, Duration.ofSeconds(5));
        CompletableFuture<GetGlobalState.Response> f = r.call("x", GetGlobalState.Response.class, () -> 2);

        r.reset(new IllegalStateException("会话结束"));

        assertThat(f).isCompletedExceptionally();
        assertThat(r.pendingCount()).isZero();
    }

    @Test
    void 发送失败与超时() {
        FutuReplyRegistry r = new FutuReplyRegistry("t", scheduler, Runnable::run, Duration.ofMillis(50));
        assertThat(r.call("x", GetGlobalState.Response.class, () -> 0)).isCompletedExceptionally();
        CompletableFuture<GetGlobalState.Response> slow = r.call("slow", GetGlobalState.Response.class, () -> 5);
        assertThatThrownBy(() -> slow.get(2, TimeUnit.SECONDS)).hasCauseInstanceOf(RequestTimeoutException.class);
    }
}
