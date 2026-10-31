package org.jdkxx.trader.gateway.futu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.futu.openapi.pb.GetGlobalState;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.jdkxx.trader.gateway.RequestRejectedException;
import org.jdkxx.trader.gateway.RequestTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class FutuReplyRegistryTest {

  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

  @AfterEach
  void tearDown() {
    scheduler.shutdownNow();
  }

  private static GetGlobalState.Response response(int retType, String msg) {
    return GetGlobalState.Response.newBuilder()
        .setRetType(retType)
        .setRetMsg(msg)
        .setS2C(
            GetGlobalState.S2C
                .newBuilder()
                .setMarketHK(1)
                .setMarketUS(1)
                .setMarketSH(1)
                .setMarketSZ(1)
                .setMarketHKFuture(1)
                .setQotLogined(true)
                .setTrdLogined(true)
                .setServerVer(1)
                .setServerBuildNo(1)
                .setTime(1)
                .setLocalTime(1))
        .build();
  }

  @Test
  void 按序列号完成并解析成功状态() throws Exception {
    FutuReplyRegistry r =
        new FutuReplyRegistry("t", scheduler, Runnable::run, Duration.ofSeconds(5));
    CompletableFuture<GetGlobalState.Response> f =
        r.call("x", GetGlobalState.Response.class, () -> 7);
    r.onReply(7, response(0, ""));
    assertThat(f.get(1, TimeUnit.SECONDS).getS2C().getQotLogined()).isTrue();
    assertThat(r.pendingCount()).isZero();
  }

  /**
   * The session ends between "sent" and "registered": this request must fail on the spot.
   *
   * <p>Before 3.1.1 it escaped reset's "every in-flight request fails" and was registered in the
   * new session's pending table. Since serial numbers restart at 1 per connection, the new
   * connection soon used the same serial number and overwrote it; the overwritten Entry's timeout
   * task {@code pending.remove(seq, entry)} then failed its comparison, the future <b>never
   * completed</b>, and the caller hung forever (found in the 2026-09-25 full-project review).
   */
  @Test
  void 发出后会话就结束_当场失败而不是永远挂着() throws Exception {
    FutuReplyRegistry r =
        new FutuReplyRegistry("t", scheduler, Runnable::run, Duration.ofSeconds(5));

    // Inside send, simulate "the connection dropped right after sending"
    CompletableFuture<GetGlobalState.Response> f =
        r.call(
            "x",
            GetGlobalState.Response.class,
            () -> {
              r.reset(new IllegalStateException("连接断了"));
              return 5;
            });

    assertThat(f).isCompletedExceptionally();
    assertThatThrownBy(() -> f.get(1, TimeUnit.SECONDS))
        .hasRootCauseInstanceOf(org.jdkxx.trader.gateway.NotConnectedException.class);
    assertThat(r.pendingCount()).as("不能留在 pending 里等着被新会话的同序列号顶替").isZero();
  }

  /**
   * The consequence of overwriting: the new session uses the same serial number and the old request
   * can never complete. This test pins down "is never overwritten".
   */
  @Test
  void 新会话复用同序列号时_旧请求已经不在pending里() throws Exception {
    FutuReplyRegistry r =
        new FutuReplyRegistry("t", scheduler, Runnable::run, Duration.ofSeconds(5));
    CompletableFuture<GetGlobalState.Response> stale =
        r.call(
            "旧",
            GetGlobalState.Response.class,
            () -> {
              r.reset(new IllegalStateException("连接断了"));
              return 1;
            });
    assertThat(stale).isCompletedExceptionally();

    CompletableFuture<GetGlobalState.Response> fresh =
        r.call("新", GetGlobalState.Response.class, () -> 1);
    r.onReply(1, response(0, ""));

    assertThat(fresh.get(1, TimeUnit.SECONDS)).as("新会话的同序列号请求照常完成").isNotNull();
    assertThat(r.pendingCount()).isZero();
  }

  @Test
  void 先到的回复不会丢() throws Exception {
    FutuReplyRegistry r =
        new FutuReplyRegistry("t", scheduler, Runnable::run, Duration.ofSeconds(5));
    r.onReply(9, response(0, ""));
    CompletableFuture<GetGlobalState.Response> f =
        r.call("x", GetGlobalState.Response.class, () -> 9);
    assertThat(f.get(1, TimeUnit.SECONDS)).isNotNull();
  }

  @Test
  void 失败状态映射为拒绝异常() {
    FutuReplyRegistry r =
        new FutuReplyRegistry("t", scheduler, Runnable::run, Duration.ofSeconds(5));
    CompletableFuture<GetGlobalState.Response> f =
        r.call("x", GetGlobalState.Response.class, () -> 3);
    r.onReply(3, response(-1, "no permission"));
    assertThatThrownBy(f::join)
        .hasCauseInstanceOf(RequestRejectedException.class)
        .hasMessageContaining("no permission");
  }

  @Test
  void 会话结束后暂存的旧回复作废_新连接同序列号拿不到() {
    // Every FTAPI_Conn numbers from 1: a reply that arrives late on the old connection after a
    // timeout must not be matched to the request with the same serial number on the new connection
    // (it was before 2.0.2)
    FutuReplyRegistry r =
        new FutuReplyRegistry("t", scheduler, Runnable::run, Duration.ofMillis(100));
    r.onReply(1, response(0, "旧连接迟到的回复"));

    r.reset(new IllegalStateException("会话结束"));
    CompletableFuture<GetGlobalState.Response> f =
        r.call("x", GetGlobalState.Response.class, () -> 1);

    assertThatThrownBy(() -> f.get(2, TimeUnit.SECONDS))
        .hasCauseInstanceOf(RequestTimeoutException.class);
  }

  @Test
  void 会话结束让在途请求失败() {
    FutuReplyRegistry r =
        new FutuReplyRegistry("t", scheduler, Runnable::run, Duration.ofSeconds(5));
    CompletableFuture<GetGlobalState.Response> f =
        r.call("x", GetGlobalState.Response.class, () -> 2);

    r.reset(new IllegalStateException("会话结束"));

    assertThat(f).isCompletedExceptionally();
    assertThat(r.pendingCount()).isZero();
  }

  @Test
  void 发送失败与超时() {
    FutuReplyRegistry r =
        new FutuReplyRegistry("t", scheduler, Runnable::run, Duration.ofMillis(50));
    assertThat(r.call("x", GetGlobalState.Response.class, () -> 0)).isCompletedExceptionally();
    CompletableFuture<GetGlobalState.Response> slow =
        r.call("slow", GetGlobalState.Response.class, () -> 5);
    assertThatThrownBy(() -> slow.get(2, TimeUnit.SECONDS))
        .hasCauseInstanceOf(RequestTimeoutException.class);
  }
}
