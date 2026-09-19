package org.jdkxx.trader.core.account;

import org.jdkxx.trader.domain.AccountKind;
import org.jdkxx.trader.domain.AccountPnl;
import org.jdkxx.trader.domain.AccountRef;
import org.jdkxx.trader.domain.AccountSummary;
import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.domain.Position;
import org.jdkxx.trader.domain.PositionPnl;
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
 * 实时账户服务。数字取自 2026-09-19 只读探针的同一时刻：逐只市值之和 505,553.69 = 汇总股票市值，
 * 现金 2,801.18 + 应计股息 379.49 + 505,553.69 = 净值 508,734.36。
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

    private static Position pos(String conId, String symbol, String qty) {
        return new Position(Broker.IBKR, ACCT, conId, symbol, symbol, "STK", "NYSE", null, "USD", new BigDecimal(qty), new BigDecimal("100"));
    }

    private static PositionPnl single(String conId, String qty, String daily, String unreal, String value) {
        return new PositionPnl(Broker.IBKR, conId, T, new BigDecimal(qty), new BigDecimal(daily), new BigDecimal(unreal), null,
                new BigDecimal(value));
    }

    private static AccountSummary summary() {
        return new AccountSummary(Broker.IBKR, ACCT, T, "USD", new BigDecimal("508734.36"), new BigDecimal("2801.18"),
                new BigDecimal("505553.69"), null, new BigDecimal("375647.03"), null, null, null, null, new BigDecimal("379.49"), Map.of());
    }

    /** 第一次读发起订阅并报 WARMING；账户号只给打码后的尾号。 */
    @Test
    void 第一次读发起订阅() {
        LiveAccountService.LiveView v = service.view();
        verify(live).startLive(eq(ACCT), eq(service));
        assertThat(v.status()).isEqualTo(LiveAccountService.Status.WARMING);
        assertThat(v.accountMask()).isNotEqualTo(ACCT).contains("*");
    }

    /** 守护：实时净值 = 现金 + 应计股息 + 逐只市值之和（实测同一时刻与汇总净值精确相等）。 */
    @Test
    void 实时净值按恒等式估算() {
        service.onSummary(summary());
        service.onPositions(List.of(pos("756733", "SPY", "210"), pos("424099317", "SGOV", "2366")));
        service.onPositionPnl(single("756733", "210", "449.15", "19828.81", "160198.49"));
        service.onPositionPnl(single("424099317", "2366", "70.97", "-189.30", "345355.20"));   // 两只凑成 505,553.69

        LiveAccountService.LiveView v = service.snapshotView();
        assertThat(v.status()).isEqualTo(LiveAccountService.Status.LIVE);
        assertThat(v.nav().estimate()).isEqualByComparingTo("508734.36");
        assertThat(v.nav().summary()).isEqualByComparingTo("508734.36");
        assertThat(v.positions()).filteredOn(LiveAccountService.LivePosition::cashEquivalent)
                .extracting(LiveAccountService.LivePosition::symbol).containsExactly("SGOV");
    }

    /** 缺任何一只的市值不估：少算一只会把净值低估几万，不如退回汇总净值。 */
    @Test
    void 缺一只市值就不估净值() {
        service.onSummary(summary());
        service.onPositions(List.of(pos("756733", "SPY", "210"), pos("424099317", "SGOV", "2366")));
        service.onPositionPnl(single("756733", "210", "449.15", "19828.81", "160198.49"));

        LiveAccountService.LiveView v = service.snapshotView();
        assertThat(v.nav().estimate()).isNull();
        assertThat(v.nav().summary()).isEqualByComparingTo("508734.36");
    }

    /** 账户盈亏还没到（首条被丢）时用逐只加总；到了就用账户的。实测两者精确相等。 */
    @Test
    void 账户盈亏没到时用逐只加总() {
        service.onSummary(summary());
        service.onPositions(List.of(pos("756733", "SPY", "210"), pos("43645865", "IBKR", "11.1142")));
        service.onPositionPnl(single("756733", "210", "449.15", "19828.81", "160198.49"));
        service.onPositionPnl(single("43645865", "11.1142", "26.67", "184.65", "1008.95"));

        LiveAccountService.Pnl p = service.snapshotView().pnl();
        assertThat(p.source()).isEqualTo("POSITIONS");
        assertThat(p.daily()).isEqualByComparingTo("475.82");

        service.onPnl(new AccountPnl(Broker.IBKR, T, new BigDecimal("946.95"), new BigDecimal("22359.71"), BigDecimal.ZERO));
        assertThat(service.snapshotView().pnl().source()).isEqualTo("ACCOUNT");
        assertThat(service.snapshotView().pnl().daily()).isEqualByComparingTo("946.95");
    }

    /** 清仓的持仓不能再算进净值。 */
    @Test
    void 清仓后逐只市值随之移除() {
        service.onSummary(summary());
        service.onPositions(List.of(pos("756733", "SPY", "210")));
        service.onPositionPnl(single("756733", "210", "449.15", "19828.81", "160198.49"));
        service.onPositions(List.of());

        LiveAccountService.LiveView v = service.snapshotView();
        assertThat(v.positions()).isEmpty();
        assertThat(v.nav().estimate()).isEqualByComparingTo("3180.67");   // 只剩现金 + 应计股息
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
}
