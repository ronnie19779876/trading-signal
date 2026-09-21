package org.jdkxx.trader.core.account;

import org.jdkxx.trader.domain.AccountKind;
import org.jdkxx.trader.domain.AccountPnl;
import org.jdkxx.trader.domain.AccountRef;
import org.jdkxx.trader.domain.AccountSummary;
import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.domain.Position;
import org.jdkxx.trader.domain.PositionPnl;
import org.jdkxx.trader.domain.PositionPrice;
import org.jdkxx.trader.gateway.BrokerGateway;
import org.jdkxx.trader.gateway.GatewayStatus;
import org.jdkxx.trader.gateway.LiveAccountGateway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 实时账户服务。数字取自 2026-09-19 生产账户的只读探针。
 * 核心约束（用户要求）：<b>只给盈透原值，不做任何折算</b>——与盈透 App 一致。
 */
class LiveAccountServiceTest {

    private static final String ACCT = "ACCT-A";
    private static final Instant T = Instant.parse("2026-09-21T14:00:00Z");

    private final BrokerGateway broker = mock(BrokerGateway.class);
    private final LiveAccountGateway live = mock(LiveAccountGateway.class);
    private final MutableClock clock = new MutableClock(T);
    private final AccountProperties props = new AccountProperties(true, "0 0 18 * * MON-FRI", "test-only-secret-0123456789",
            List.of("SGOV"), new BigDecimal("0.002"), BigDecimal.ONE);
    private final LiveAccountService service = new LiveAccountService(props, null, broker, live, clock);

    LiveAccountServiceTest() {
        when(broker.enabled()).thenReturn(true);
        when(broker.status()).thenReturn(GatewayStatus.connected("已连接"));
        when(broker.accounts()).thenReturn(CompletableFuture.completedFuture(
                List.of(new AccountRef(Broker.IBKR, ACCT, AccountKind.LIVE, null))));
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static Position pos(String conId, String symbol, String qty, String avg) {
        return new Position(Broker.IBKR, ACCT, conId, symbol, symbol, "STK", "NYSE", null, "USD", new BigDecimal(qty), new BigDecimal(avg));
    }

    private static PositionPnl single(String conId, String qty, String daily, String unreal, String value) {
        return new PositionPnl(Broker.IBKR, conId, T, new BigDecimal(qty), new BigDecimal(daily), new BigDecimal(unreal), null,
                new BigDecimal(value));
    }

    private static AccountSummary summary() {
        return new AccountSummary(Broker.IBKR, ACCT, T, "USD", new BigDecimal("508769.74"), new BigDecimal("2801.18"),
                new BigDecimal("505589.07"), null, new BigDecimal("375673.12"), null, null, null, null, new BigDecimal("379.49"), Map.of());
    }

    /** 第一次读发起订阅并报 WARMING；账户号只给打码后的形式。 */
    @Test
    void 第一次读发起订阅() {
        LiveAccountService.LiveView v = service.view();
        verify(live).startLive(eq(ACCT), eq(service));
        assertThat(v.status()).isEqualTo(LiveAccountService.Status.WARMING);
        assertThat(v.accountMask()).isNotEqualTo(ACCT).contains("*");
    }

    /**
     * 守护：全是盈透原值——净值是汇总的 NetLiquidation（不是"现金 + 股息 + Σ市值"的估算），
     * 现价是行情最新价（不是"市值 ÷ 数量"：GOOG 实测 346.08 对 344.41）。
     */
    @Test
    void 净值与现价都是盈透原值() {
        service.onSummary(summary());
        service.onPositions(List.of(pos("208813720", "GOOG", "62", "346.81"), pos("424099317", "SGOV", "2366", "100.67")));
        service.onPositionPnl(single("208813720", "62", "45.26", "-148.58", "21353.42"));   // 21353.42 / 62 = 344.41
        service.onPositionPnl(single("424099317", "2366", "82.81", "-177.45", "237994.94"));
        service.onPositionPrice(new PositionPrice(Broker.IBKR, "208813720", T, new BigDecimal("346.08"), null, false));

        LiveAccountService.LiveView v = service.snapshotView();
        assertThat(v.status()).isEqualTo(LiveAccountService.Status.LIVE);
        assertThat(v.money().netLiquidation()).isEqualByComparingTo("508769.74");
        LiveAccountService.LivePosition goog = v.positions().get(0);
        assertThat(goog.last()).isEqualByComparingTo("346.08");
        assertThat(goog.marketValue()).isEqualByComparingTo("21353.42");
        assertThat(v.positions().get(1).last()).isNull();   // 行情还没到就是空，不用市值倒推
        assertThat(v.positions()).filteredOn(LiveAccountService.LivePosition::cashEquivalent)
                .extracting(LiveAccountService.LivePosition::symbol).containsExactly("SGOV");
    }

    /** 守护：账户盈亏没到时就是空，不用逐只加总顶替（那是本系统的计算，不是盈透的数）。 */
    @Test
    void 账户盈亏没到时为空不加总() {
        service.onSummary(summary());
        service.onPositions(List.of(pos("756733", "SPY", "210", "668.43")));
        service.onPositionPnl(single("756733", "210", "476.45", "19656.12", "160225.80"));

        assertThat(service.snapshotView().pnl()).isNull();

        service.onPnl(new AccountPnl(Broker.IBKR, T, new BigDecimal("915.99"), new BigDecimal("22328.76"), BigDecimal.ZERO));
        assertThat(service.snapshotView().pnl().daily()).isEqualByComparingTo("915.99");
    }

    /** 清仓的持仓连同它的市值与现价一起拿掉。 */
    @Test
    void 清仓后移除() {
        service.onSummary(summary());
        service.onPositions(List.of(pos("756733", "SPY", "210", "668.43")));
        service.onPositionPnl(single("756733", "210", "476.45", "19656.12", "160225.80"));
        service.onPositionPrice(new PositionPrice(Broker.IBKR, "756733", T, new BigDecimal("762.98"), null, false));
        service.onPositions(List.of());
        service.onPositions(List.of(pos("756733", "SPY", "10", "700")));   // 又买回来：旧的市值与现价不能沿用

        LiveAccountService.LivePosition spy = service.snapshotView().positions().get(0);
        assertThat(spy.marketValue()).isNull();
        assertThat(spy.last()).isNull();
    }

    /** 守护：按需订阅——5 分钟没人读就退订；有人读就一直订着。 */
    @Test
    void 闲置五分钟退订() {
        service.view();
        when(live.liveActive()).thenReturn(true);

        clock.now = T.plusSeconds(4 * 60);
        service.stopIfIdle();
        verify(live, never()).stopLive();

        clock.now = T.plusSeconds(5 * 60);
        service.stopIfIdle();
        verify(live).stopLive();
    }

    @Test
    void 网关没连上时不订阅并说明原因() {
        when(broker.status()).thenReturn(GatewayStatus.disconnected("隧道断了"));
        LiveAccountService.LiveView v = service.view();
        assertThat(v.status()).isEqualTo(LiveAccountService.Status.UNAVAILABLE);
        assertThat(v.detail()).contains("未连接");
        verify(live, never()).startLive(any(), any());
    }

    /**
     * 守护：与盈透 App 同口径的派生项，数字取自 2026-09-19 用户给的 GOOG 截图——
     * 涨跌 +2.40 / +0.70%、Cost Basis 21,502、% of Portfolio 4.20%，盈透原值 Today 45.26、Unrealized -148.58。
     */
    @Test
    void 派生项与盈透App截图一致() {
        service.onSummary(summary());   // 净值 508,769.74
        service.onPositions(List.of(pos("208813720", "GOOG", "62", "346.80645485")));
        service.onPositionPnl(single("208813720", "62", "45.26", "-148.58", "21353.42"));
        service.onPositionPrice(new PositionPrice(Broker.IBKR, "208813720", T, new BigDecimal("346.082"), new BigDecimal("343.68"), false));

        LiveAccountService.LivePosition g = service.snapshotView().positions().get(0);
        assertThat(g.change()).isEqualByComparingTo("2.40");
        assertThat(g.changePct()).isEqualByComparingTo("0.70");
        assertThat(g.costBasis()).isEqualByComparingTo("21502.00");
        assertThat(g.portfolioPct()).isEqualByComparingTo("4.20");
        assertThat(g.dailyPnl()).isEqualByComparingTo("45.26");
        assertThat(g.unrealizedPnl()).isEqualByComparingTo("-148.58");
    }

    /** 前收还没到时涨跌为空，不拿别的数凑。 */
    @Test
    void 前收没到时涨跌为空() {
        service.onSummary(summary());
        service.onPositions(List.of(pos("208813720", "GOOG", "62", "346.80645485")));
        service.onPositionPrice(new PositionPrice(Broker.IBKR, "208813720", T, new BigDecimal("346.082"), null, false));

        LiveAccountService.LivePosition g = service.snapshotView().positions().get(0);
        assertThat(g.change()).isNull();
        assertThat(g.changePct()).isNull();
        assertThat(g.portfolioPct()).isNull();   // 市值（逐只盈亏）也还没到
    }

    // ---- 错误恢复（2026-09-21 生产：断开重连恢复后，页面仍挂着那条 322） ----

    /** 出错的那一路再有推送就算恢复：清掉 lastError，别让界面一直挂旧错误。 */
    @Test
    void 账户汇总恢复后清掉错误() {
        service.view();   // 发起订阅
        when(live.liveActive()).thenReturn(true);   // 订上了，后面的 view() 不再重订（重订会 clear()）
        service.onLiveError("实时账户汇总", new org.jdkxx.trader.gateway.RequestRejectedException(
                Broker.IBKR, 322, "Maximum number of account summary requests exceeded"));
        assertThat(service.view().lastError()).contains("322");

        service.onSummary(summary());

        assertThat(service.view().lastError()).isNull();
    }

    /** 别的路推送不清账户汇总的错误：问题还在就得留着。 */
    @Test
    void 别的推送不清账户汇总的错误() {
        service.view();
        when(live.liveActive()).thenReturn(true);
        service.onLiveError("实时账户汇总", new org.jdkxx.trader.gateway.RequestRejectedException(
                Broker.IBKR, 322, "Maximum number of account summary requests exceeded"));

        service.onPositions(List.of(pos("1", "SPY", "210", "668.43")));
        service.onPnl(new AccountPnl(Broker.IBKR, T, new BigDecimal("915.99"), new BigDecimal("22328.76"), BigDecimal.ZERO));

        assertThat(service.view().lastError()).contains("322");
    }
}
