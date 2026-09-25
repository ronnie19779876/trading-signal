package org.jdkxx.trader.gateway.ibkr;

import org.jdkxx.trader.domain.AccountSummary;
import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.gateway.GatewayException;
import org.jdkxx.trader.gateway.LiveAccountListener;
import org.jdkxx.trader.gateway.RequestRejectedException;
import org.jdkxx.trader.gateway.ibkr.mapper.IbkrAccounts;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 账户汇总常驻订阅。行为依据见 {@link IbkrAccountSummaryFeed} 的类注释（2026-09-23 生产实测）：
 * 盈透的名额按<b>订过的次数</b>算、取消不释放，所以"整个连接周期只订一次"是这个类唯一要守住的性质。
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

    /** 这条是整个改动的要害：反复订阅只能发一次券商请求，否则名额迟早耗光。 */
    @Test
    void 同一会话反复订阅只发一次请求() {
        assertThat(feed.subscribe()).isTrue();
        feed.subscribe();
        feed.subscribe();
        feed.subscribe();

        verify(wire.client, times(1)).reqAccountSummary(anyInt(), eq("All"), eq(IbkrAccounts.SUMMARY_TAGS));
        verify(wire.client, never()).cancelAccountSummary(anyInt());
    }

    /** 实时账户闲置退订时只摘监听器，常驻订阅不动——退了名额也不还。 */
    /**
     * 1101「连接恢复、订阅数据丢失」：会话对象不变，但券商已经把订阅丢了，必须重订。
     *
     * <p>3.1.1 前这里会走短路（subscribed() 仍为 true）什么都不做，资金数据从此冻结到下次真断线：
     * 快照作业等 90 秒后 FAILED，而 /api/account/live 的净值停在 1101 之前、状态还是 LIVE
     * （2026-09-25 全项目审查发现）。
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
        o.verify(wire.client).cancelAccountSummary(anyInt());      // 先退掉旧的那条
        o.verify(wire.client).reqAccountSummary(anyInt(), eq("All"), eq(IbkrAccounts.SUMMARY_TAGS));
        verify(wire.client, times(2)).reqAccountSummary(anyInt(), eq("All"), eq(IbkrAccounts.SUMMARY_TAGS));
    }

    /** 没有数据丢失的普通调用仍然只订一次——常驻的性质不能被上面那条破坏。 */
    @Test
    void 普通重复调用仍然只订一次() {
        feed.subscribe();
        feed.subscribe();
        feed.subscribe(false);

        verify(wire.client, times(1)).reqAccountSummary(anyInt(), eq("All"), eq(IbkrAccounts.SUMMARY_TAGS));
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

    /** 挂上监听器时若已有数据立刻补一条，不用等下一批（券商约 3 分钟一批）。 */
    @Test
    void 挂监听器时立刻补发已有数据() {
        feed.subscribe();
        pushBatchAndFlush();
        assertThat(rec.summaries).isEmpty();          // 还没人监听

        feed.listener(ACCT, rec);

        assertThat(rec.summaries).hasSize(1);
        assertThat(rec.summaries.get(0).netLiquidation()).isEqualByComparingTo("508729.25");
    }

    /** 逐条推送、没有 End：攒到静默再发一次。 */
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

    /** 换会话（断线重连）重订前，按上一次的 id 先取消——盈透侧旧订阅可能还挂着。 */
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

        wire.handlers.get(SUB).error(new RequestRejectedException(
                Broker.IBKR, IbkrAccountSummaryFeed.LIMIT_CODE, "Maximum number of account summary requests exceeded"));
        assertThat(wire.pending(Duration.ofSeconds(IbkrAccountSummaryFeed.RETRY_SECONDS))).isEqualTo(1);
        wire.fire(Duration.ofSeconds(IbkrAccountSummaryFeed.RETRY_SECONDS));

        verify(wire.client).cancelAccountSummary(SUB);
        verify(wire.client, times(2)).reqAccountSummary(anyInt(), eq("All"), eq(IbkrAccounts.SUMMARY_TAGS));
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

        wire.handlers.get(SUB).error(new RequestRejectedException(Broker.IBKR, 354, "Requested market data is not subscribed"));

        assertThat(wire.pending(Duration.ofSeconds(IbkrAccountSummaryFeed.RETRY_SECONDS))).isZero();
        verify(wire.client, times(1)).reqAccountSummary(anyInt(), eq("All"), eq(IbkrAccounts.SUMMARY_TAGS));
    }

    // ------------------------------------------------------------------ 快照取数（3.0.9 起走常驻订阅）

    @Test
    void 已有新鲜数据时直接返回_不再发请求() {
        feed.subscribe();
        pushBatchAndFlush();

        CompletableFuture<AccountSummary> out = new CompletableFuture<>();
        feed.request(ACCT, out);

        assertThat(out).isCompleted();
        assertThat(out.join().netLiquidation()).isEqualByComparingTo("508729.25");
        verify(wire.client, times(1)).reqAccountSummary(anyInt(), eq("All"), eq(IbkrAccounts.SUMMARY_TAGS));
    }

    /**
     * 数据超过 MAX_AGE 就不能直接给，要等下一批。
     *
     * <p>这条分支此前**在构造上不可能**被测到：{@code FakeWire.now()} 返回定值，
     * 13 个 feed 测试全落在 age=0 的新鲜分支（2026-09-25 全项目审查发现）。
     * 它守的是真实故障形态——常驻订阅停推（1101 之后就是这样），
     * 快照不能拿十分钟前的净值当当天的。
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
        verify(wire.client, times(1)).reqAccountSummary(anyInt(), eq("All"), eq(IbkrAccounts.SUMMARY_TAGS));

        pushBatchAndFlush();
        assertThat(out).isCompleted();
    }

    /** 边界：刚好卡在 MAX_AGE 上仍算新鲜（判定是 <=）。 */
    @Test
    void 正好等于MAX_AGE仍算新鲜() {
        feed.subscribe();
        pushBatchAndFlush();
        wire.advance(IbkrAccountSummaryFeed.MAX_AGE);

        CompletableFuture<AccountSummary> out = new CompletableFuture<>();
        feed.request(ACCT, out);

        assertThat(out).isCompleted();
    }

    /** 刚重连还没收到首批：等下一批，到了就完成。 */
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
        assertThat(wire.pending(IbkrAccountSummaryFeed.WAIT)).isZero();   // 超时任务已撤
    }

    /** 等不到就明确失败，让快照作业记 FAILED 由补偿重试，而不是拿旧值糊弄。 */
    @Test
    void 等待超时就失败() {
        feed.subscribe();
        CompletableFuture<AccountSummary> out = new CompletableFuture<>();
        feed.request(ACCT, out);

        wire.fire(IbkrAccountSummaryFeed.WAIT);

        assertThat(out).isCompletedExceptionally();
        assertThat(out.handle((v, e) -> e.getMessage()).join()).contains("没有推送");
    }

    /** 断开时等待中的请求立刻失败，不干等 90 秒。 */
    @Test
    void 断开时等待中的请求立刻失败() {
        feed.subscribe();
        CompletableFuture<AccountSummary> out = new CompletableFuture<>();
        feed.request(ACCT, out);

        feed.stop();

        assertThat(out).isCompletedExceptionally();
        assertThat(feed.subscribed()).isFalse();
    }

    /** 网关没连上时不假装能取：直接失败。 */
    @Test
    void 未连接时直接失败() {
        wire.connected = false;
        CompletableFuture<AccountSummary> out = new CompletableFuture<>();
        feed.request(ACCT, out);

        assertThat(out).isCompletedExceptionally();
        assertThat(out.handle((v, e) -> e).join()).isInstanceOf(GatewayException.class);
    }
}
