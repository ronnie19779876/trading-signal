package org.jdkxx.trader.gateway.ibkr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.jdkxx.trader.domain.AccountSummary;
import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.gateway.GatewayException;
import org.jdkxx.trader.gateway.RequestRejectedException;
import org.jdkxx.trader.gateway.ibkr.mapper.IbkrAccounts;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * The standing account-summary subscription. The behavior is grounded in {@link
 * IbkrAccountSummaryFeed}'s class comment (measured in production on 2026-09-23): IBKR counts slots
 * by <b>subscriptions ever made</b> and cancelling does not free them, so "subscribe only once per
 * connection cycle" is the one property this class must uphold.
 */
class IbkrAccountSummaryFeedTest {

  private static final String ACCT = "ACCT-A";

  private final IbkrLiveAccountTest.FakeWire wire = new IbkrLiveAccountTest.FakeWire();
  private final IbkrLiveAccountTest.Recorder rec = new IbkrLiveAccountTest.Recorder();
  private final IbkrAccountSummaryFeed feed = new IbkrAccountSummaryFeed(wire);

  private static final int SUB = 100;

  private void push(String tag, String value) {
    wire.handlers.get(SUB).item(new IbkrAccounts.SummaryRow(ACCT, tag, value, "USD"));
  }

  private void pushBatchAndFlush() {
    push("NetLiquidation", "508729.25");
    push("TotalCashValue", "2801.18");
    push("$LEDGER-StockMarketValue", "505548.58");
    wire.fire(Duration.ofMillis(IbkrAccountSummaryFeed.DEBOUNCE_MS));
  }

  /**
   * This is the crux of the whole change: repeated subscribe calls may send only one broker
   * request, otherwise the slots run out sooner or later.
   */
  @Test
  void 同一会话反复订阅只发一次请求() {
    assertThat(feed.subscribe()).isTrue();
    feed.subscribe();
    feed.subscribe();
    feed.subscribe();

    verify(wire.client, times(1))
        .reqAccountSummary(anyInt(), eq("All"), eq(IbkrAccounts.SUMMARY_TAGS));
    verify(wire.client, never()).cancelAccountSummary(anyInt());
  }

  /**
   * When the live account goes idle and unsubscribes, only the listener is detached and the
   * standing subscription stays; cancelling would not give the slot back.
   */
  /**
   * 1101 "connection restored, subscription data lost": the session object is unchanged, but the
   * broker has dropped the subscription, so it must be re-subscribed.
   *
   * <p>Before 3.1.1 this short-circuited (subscribed() still true) and did nothing, freezing the
   * funds data until the next real disconnect: the snapshot job was FAILED after waiting 90
   * seconds, while /api/account/live kept the net liquidation from before the 1101 with its status
   * still LIVE (found in the full-project review on 2026-09-25).
   */
  @Test
  void 数据丢失后重订_会话不变也要重发请求() {
    feed.subscribe();
    pushBatchAndFlush();
    Object sameSession = wire.sessionToken();

    feed.subscribe(true);

    assertThat(wire.sessionToken()).as("1101 不换会话").isSameAs(sameSession);
    InOrder o = inOrder(wire.client);
    o.verify(wire.client).reqAccountSummary(anyInt(), eq("All"), eq(IbkrAccounts.SUMMARY_TAGS));
    o.verify(wire.client).cancelAccountSummary(anyInt()); // cancel the old one first
    o.verify(wire.client).reqAccountSummary(anyInt(), eq("All"), eq(IbkrAccounts.SUMMARY_TAGS));
    verify(wire.client, times(2))
        .reqAccountSummary(anyInt(), eq("All"), eq(IbkrAccounts.SUMMARY_TAGS));
  }

  /**
   * An ordinary call without data loss still subscribes only once: the standing property must not
   * be broken by the case above.
   */
  @Test
  void 普通重复调用仍然只订一次() {
    feed.subscribe();
    feed.subscribe();
    feed.subscribe(false);

    verify(wire.client, times(1))
        .reqAccountSummary(anyInt(), eq("All"), eq(IbkrAccounts.SUMMARY_TAGS));
    verify(wire.client, never()).cancelAccountSummary(anyInt());
  }

  @Test
  void 摘掉监听器不退订() {
    feed.subscribe();
    feed.listener(ACCT, rec);
    feed.listener(null, null);

    verify(wire.client, never()).cancelAccountSummary(anyInt());
    assertThat(feed.subscribed()).isTrue();
  }

  /**
   * Attaching a listener while data exists delivers one update immediately instead of waiting for
   * the next batch (the broker sends one about every 3 minutes).
   */
  @Test
  void 挂监听器时立刻补发已有数据() {
    feed.subscribe();
    pushBatchAndFlush();
    assertThat(rec.summaries).isEmpty(); // nobody listening yet

    feed.listener(ACCT, rec);

    assertThat(rec.summaries).hasSize(1);
    assertThat(rec.summaries.get(0).netLiquidation()).isEqualByComparingTo("508729.25");
  }

  /** Rows pushed one by one with no End: collect until quiet, then emit once. */
  @Test
  void 逐条推送攒批后发一次() {
    feed.subscribe();
    feed.listener(ACCT, rec);
    push("NetLiquidation", "508729.25");
    push("TotalCashValue", "2801.18");
    assertThat(wire.pending(Duration.ofMillis(IbkrAccountSummaryFeed.DEBOUNCE_MS))).isEqualTo(1);
    assertThat(rec.summaries).isEmpty();

    wire.fire(Duration.ofMillis(IbkrAccountSummaryFeed.DEBOUNCE_MS));

    assertThat(rec.summaries).hasSize(1);
    assertThat(rec.summaries.get(0).totalCash()).isEqualByComparingTo("2801.18");
  }

  /**
   * Before re-subscribing in a new session (reconnect), first cancel by the previous id: the old
   * subscription may still hang on IBKR's side.
   */
  @Test
  void 换会话重订前先取消上一次的订阅() {
    feed.subscribe();
    wire.token = new Object();

    feed.subscribe();

    InOrder o = inOrder(wire.client);
    o.verify(wire.client).cancelAccountSummary(SUB);
    o.verify(wire.client).reqAccountSummary(anyInt(), eq("All"), eq(IbkrAccounts.SUMMARY_TAGS));
  }

  @Test
  void 被322拒后自动重订() {
    feed.subscribe();

    wire.handlers
        .get(SUB)
        .error(
            new RequestRejectedException(
                Broker.IBKR,
                IbkrAccountSummaryFeed.LIMIT_CODE,
                "Maximum number of account summary requests exceeded"));
    assertThat(wire.pending(Duration.ofSeconds(IbkrAccountSummaryFeed.RETRY_SECONDS))).isEqualTo(1);
    wire.fire(Duration.ofSeconds(IbkrAccountSummaryFeed.RETRY_SECONDS));

    verify(wire.client).cancelAccountSummary(SUB);
    verify(wire.client, times(2))
        .reqAccountSummary(anyInt(), eq("All"), eq(IbkrAccounts.SUMMARY_TAGS));
  }

  @Test
  void 重订到上限就停() {
    feed.subscribe();
    for (int i = 0; i < IbkrAccountSummaryFeed.MAX_RETRIES + 2; i++) {
      feed.retrySubscribe();
    }

    verify(wire.client, times(1 + IbkrAccountSummaryFeed.MAX_RETRIES))
        .reqAccountSummary(anyInt(), eq("All"), eq(IbkrAccounts.SUMMARY_TAGS));
  }

  @Test
  void 非322的错误不重订() {
    feed.subscribe();

    wire.handlers
        .get(SUB)
        .error(
            new RequestRejectedException(
                Broker.IBKR, 354, "Requested market data is not subscribed"));

    assertThat(wire.pending(Duration.ofSeconds(IbkrAccountSummaryFeed.RETRY_SECONDS))).isZero();
    verify(wire.client, times(1))
        .reqAccountSummary(anyInt(), eq("All"), eq(IbkrAccounts.SUMMARY_TAGS));
  }

  // ------------------------------------------------------------------ snapshot reads (served by
  // the standing subscription since 3.0.9)

  @Test
  void 已有新鲜数据时直接返回_不再发请求() {
    feed.subscribe();
    pushBatchAndFlush();

    CompletableFuture<AccountSummary> out = new CompletableFuture<>();
    feed.request(ACCT, out);

    assertThat(out).isCompleted();
    assertThat(out.join().netLiquidation()).isEqualByComparingTo("508729.25");
    verify(wire.client, times(1))
        .reqAccountSummary(anyInt(), eq("All"), eq(IbkrAccounts.SUMMARY_TAGS));
  }

  /**
   * Data older than MAX_AGE must not be served directly; wait for the next batch.
   *
   * <p>This branch used to be **impossible to reach by construction**: {@code FakeWire.now()}
   * returned a constant, so all 13 feed tests landed in the age=0 fresh branch (found in the
   * full-project review on 2026-09-25). It guards a real failure mode: the standing subscription
   * stops pushing (which is what happens after a 1101), and the snapshot must not pass off a
   * ten-minute-old net liquidation as today's.
   */
  @Test
  void 数据过了MAX_AGE就不算新鲜_要等下一批() {
    feed.subscribe();
    pushBatchAndFlush();
    wire.advance(IbkrAccountSummaryFeed.MAX_AGE.plusSeconds(1));

    CompletableFuture<AccountSummary> out = new CompletableFuture<>();
    feed.request(ACCT, out);

    assertThat(out).as("十分钟前的数据不能直接给").isNotCompleted();
    assertThat(wire.pending(IbkrAccountSummaryFeed.WAIT)).isEqualTo(1);
    verify(wire.client, times(1))
        .reqAccountSummary(anyInt(), eq("All"), eq(IbkrAccounts.SUMMARY_TAGS));

    pushBatchAndFlush();
    assertThat(out).isCompleted();
  }

  /** Boundary: exactly at MAX_AGE still counts as fresh (the test is &lt;=). */
  @Test
  void 正好等于MAX_AGE仍算新鲜() {
    feed.subscribe();
    pushBatchAndFlush();
    wire.advance(IbkrAccountSummaryFeed.MAX_AGE);

    CompletableFuture<AccountSummary> out = new CompletableFuture<>();
    feed.request(ACCT, out);

    assertThat(out).isCompleted();
  }

  /** Just reconnected, no first batch yet: wait for the next batch and complete when it arrives. */
  @Test
  void 还没有数据时等下一批推送() {
    feed.subscribe();
    CompletableFuture<AccountSummary> out = new CompletableFuture<>();
    feed.request(ACCT, out);
    assertThat(out).isNotCompleted();
    assertThat(wire.pending(IbkrAccountSummaryFeed.WAIT)).isEqualTo(1);

    pushBatchAndFlush();

    assertThat(out).isCompleted();
    assertThat(out.join().totalCash()).isEqualByComparingTo("2801.18");
    assertThat(wire.pending(IbkrAccountSummaryFeed.WAIT))
        .isZero(); // the timeout task has been cancelled
  }

  /**
   * If nothing arrives, fail explicitly so the snapshot job records FAILED and the catch-up
   * retries, instead of fobbing it off with stale values.
   */
  @Test
  void 等待超时就失败() {
    feed.subscribe();
    CompletableFuture<AccountSummary> out = new CompletableFuture<>();
    feed.request(ACCT, out);

    wire.fire(IbkrAccountSummaryFeed.WAIT);

    assertThat(out).isCompletedExceptionally();
    assertThat(out.handle((v, e) -> e.getMessage()).join()).contains("没有推送");
  }

  /** On disconnect, waiting requests fail right away instead of sitting out the 90 seconds. */
  @Test
  void 断开时等待中的请求立刻失败() {
    feed.subscribe();
    CompletableFuture<AccountSummary> out = new CompletableFuture<>();
    feed.request(ACCT, out);

    feed.stop();

    assertThat(out).isCompletedExceptionally();
    assertThat(feed.subscribed()).isFalse();
  }

  /** When the gateway is not connected, do not pretend it can be read: fail straight away. */
  @Test
  void 未连接时直接失败() {
    wire.connected = false;
    CompletableFuture<AccountSummary> out = new CompletableFuture<>();
    feed.request(ACCT, out);

    assertThat(out).isCompletedExceptionally();
    assertThat(out.handle((v, e) -> e).join()).isInstanceOf(GatewayException.class);
  }
}
