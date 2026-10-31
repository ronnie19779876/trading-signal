package org.jdkxx.trader.core.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
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

/**
 * Real-time account service. Numbers come from the read-only probe of the production account on
 * 2026-09-19. Core constraint (user requirement): <b>only IBKR raw values, no conversion of any
 * kind</b>, matching the IBKR app.
 */
class LiveAccountServiceTest {

  private static final String ACCT = "ACCT-A";
  private static final Instant T = Instant.parse("2026-09-21T14:00:00Z");

  private final BrokerGateway broker = mock(BrokerGateway.class);
  private final LiveAccountGateway live = mock(LiveAccountGateway.class);
  private final MutableClock clock = new MutableClock(T);
  private final AccountProperties props =
      new AccountProperties(
          true,
          "0 0 18 * * MON-FRI",
          "test-only-secret-0123456789",
          List.of("SGOV"),
          new BigDecimal("0.002"),
          BigDecimal.ONE);
  private final LiveAccountService service =
      new LiveAccountService(props, null, broker, live, clock);

  LiveAccountServiceTest() {
    when(broker.enabled()).thenReturn(true);
    when(broker.status()).thenReturn(GatewayStatus.connected("已连接"));
    when(broker.accounts())
        .thenReturn(
            CompletableFuture.completedFuture(
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

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private static Position pos(String conId, String symbol, String qty, String avg) {
    return new Position(
        Broker.IBKR,
        ACCT,
        conId,
        symbol,
        symbol,
        "STK",
        "NYSE",
        null,
        "USD",
        new BigDecimal(qty),
        new BigDecimal(avg));
  }

  private static PositionPnl single(
      String conId, String qty, String daily, String unreal, String value) {
    return new PositionPnl(
        Broker.IBKR,
        conId,
        T,
        new BigDecimal(qty),
        new BigDecimal(daily),
        new BigDecimal(unreal),
        null,
        new BigDecimal(value));
  }

  private static AccountSummary summary() {
    return new AccountSummary(
        Broker.IBKR,
        ACCT,
        T,
        "USD",
        new BigDecimal("508769.74"),
        new BigDecimal("2801.18"),
        new BigDecimal("505589.07"),
        null,
        new BigDecimal("375673.12"),
        null,
        null,
        null,
        null,
        new BigDecimal("379.49"),
        BigDecimal.ZERO,
        Map.of());
  }

  /**
   * The first read starts the subscription and reports WARMING; the account number is only given
   * masked.
   */
  @Test
  void 第一次读发起订阅() {
    LiveAccountService.LiveView v = service.view();
    verify(live).startLive(eq(ACCT), eq(service));
    assertThat(v.status()).isEqualTo(LiveAccountService.Status.WARMING);
    assertThat(v.accountMask()).isNotEqualTo(ACCT).contains("*");
  }

  /**
   * Guard: everything is an IBKR raw value. Net liquidation is the summary's NetLiquidation (not an
   * estimate of "cash + dividends + sum of market values"), and the price is the market-data last
   * price (not "market value / quantity": measured GOOG 346.08 vs 344.41).
   */
  @Test
  void 净值与现价都是盈透原值() {
    service.onSummary(summary());
    service.onPositions(
        List.of(
            pos("208813720", "GOOG", "62", "346.81"), pos("424099317", "SGOV", "2366", "100.67")));
    service.onPositionPnl(
        single("208813720", "62", "45.26", "-148.58", "21353.42")); // 21353.42 / 62 = 344.41
    service.onPositionPnl(single("424099317", "2366", "82.81", "-177.45", "237994.94"));
    service.onPositionPrice(
        new PositionPrice(Broker.IBKR, "208813720", T, new BigDecimal("346.08"), null, false));

    LiveAccountService.LiveView v = service.snapshotView();
    assertThat(v.status()).isEqualTo(LiveAccountService.Status.LIVE);
    assertThat(v.money().netLiquidation()).isEqualByComparingTo("508769.74");
    LiveAccountService.LivePosition goog = v.positions().get(0);
    assertThat(goog.last()).isEqualByComparingTo("346.08");
    assertThat(goog.marketValue()).isEqualByComparingTo("21353.42");
    assertThat(v.positions().get(1).last())
        .isNull(); // null until market data arrives; not back-computed from market value
    assertThat(v.positions())
        .filteredOn(LiveAccountService.LivePosition::cashEquivalent)
        .extracting(LiveAccountService.LivePosition::symbol)
        .containsExactly("SGOV");
  }

  /**
   * Guard: account P&amp;L is null until it arrives; it is not replaced by the per-position sum
   * (that would be this system's calculation, not IBKR's number).
   */
  @Test
  void 账户盈亏没到时为空不加总() {
    service.onSummary(summary());
    service.onPositions(List.of(pos("756733", "SPY", "210", "668.43")));
    service.onPositionPnl(single("756733", "210", "476.45", "19656.12", "160225.80"));

    assertThat(service.snapshotView().pnl()).isNull();

    service.onPnl(
        new AccountPnl(
            Broker.IBKR, T, new BigDecimal("915.99"), new BigDecimal("22328.76"), BigDecimal.ZERO));
    assertThat(service.snapshotView().pnl().daily()).isEqualByComparingTo("915.99");
  }

  /** A closed position is removed together with its market value and price. */
  @Test
  void 清仓后移除() {
    service.onSummary(summary());
    service.onPositions(List.of(pos("756733", "SPY", "210", "668.43")));
    service.onPositionPnl(single("756733", "210", "476.45", "19656.12", "160225.80"));
    service.onPositionPrice(
        new PositionPrice(Broker.IBKR, "756733", T, new BigDecimal("762.98"), null, false));
    service.onPositions(List.of());
    service.onPositions(
        List.of(
            pos(
                "756733", "SPY", "10",
                "700"))); // bought back: the old market value and price must not carry over

    LiveAccountService.LivePosition spy = service.snapshotView().positions().get(0);
    assertThat(spy.marketValue()).isNull();
    assertThat(spy.last()).isNull();
  }

  /**
   * Guard: on-demand subscription; unsubscribes after 5 minutes without readers and stays
   * subscribed while read.
   */
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
   * Guard: derived values on the IBKR app's basis; numbers from the GOOG screenshot the user
   * provided on 2026-09-19: change +2.40 / +0.70%, Cost Basis 21,502, % of Portfolio 4.20%, IBKR
   * raw values Today 45.26, Unrealized -148.58.
   */
  @Test
  void 派生项与盈透App截图一致() {
    service.onSummary(summary()); // net liquidation 508,769.74
    service.onPositions(List.of(pos("208813720", "GOOG", "62", "346.80645485")));
    service.onPositionPnl(single("208813720", "62", "45.26", "-148.58", "21353.42"));
    service.onPositionPrice(
        new PositionPrice(
            Broker.IBKR,
            "208813720",
            T,
            new BigDecimal("346.082"),
            new BigDecimal("343.68"),
            false));

    LiveAccountService.LivePosition g = service.snapshotView().positions().get(0);
    assertThat(g.change()).isEqualByComparingTo("2.40");
    assertThat(g.changePct()).isEqualByComparingTo("0.70");
    assertThat(g.costBasis()).isEqualByComparingTo("21502.00");
    assertThat(g.portfolioPct()).isEqualByComparingTo("4.20");
    assertThat(g.dailyPnl()).isEqualByComparingTo("45.26");
    assertThat(g.unrealizedPnl()).isEqualByComparingTo("-148.58");
  }

  /**
   * Change is null until the prior close arrives; it is not patched together from other numbers.
   */
  @Test
  void 前收没到时涨跌为空() {
    service.onSummary(summary());
    service.onPositions(List.of(pos("208813720", "GOOG", "62", "346.80645485")));
    service.onPositionPrice(
        new PositionPrice(Broker.IBKR, "208813720", T, new BigDecimal("346.082"), null, false));

    LiveAccountService.LivePosition g = service.snapshotView().positions().get(0);
    assertThat(g.change()).isNull();
    assertThat(g.changePct()).isNull();
    assertThat(g.portfolioPct()).isNull(); // market value (per-position P&L) has not arrived either
  }

  // ---- error recovery (production 2026-09-21: after a disconnect and reconnect the page still
  // showed that 322) ----

  /**
   * A new push on the failed feed counts as recovery: clear lastError so the page does not keep
   * showing the old error.
   */
  @Test
  void 账户汇总恢复后清掉错误() {
    service.view(); // starts the subscription
    when(live.liveActive())
        .thenReturn(
            // subscribed: later view() calls do not re-subscribe (re-subscribing would clear())
            true);
    service.onLiveError(
        "实时账户汇总",
        new org.jdkxx.trader.gateway.RequestRejectedException(
            Broker.IBKR, 322, "Maximum number of account summary requests exceeded"));
    assertThat(service.view().lastError()).contains("322");

    service.onSummary(summary());

    assertThat(service.view().lastError()).isNull();
  }

  /**
   * A push on another feed does not clear the account-summary error: while the problem persists it
   * must stay.
   */
  @Test
  void 别的推送不清账户汇总的错误() {
    service.view();
    when(live.liveActive()).thenReturn(true);
    service.onLiveError(
        "实时账户汇总",
        new org.jdkxx.trader.gateway.RequestRejectedException(
            Broker.IBKR, 322, "Maximum number of account summary requests exceeded"));

    service.onPositions(List.of(pos("1", "SPY", "210", "668.43")));
    service.onPnl(
        new AccountPnl(
            Broker.IBKR, T, new BigDecimal("915.99"), new BigDecimal("22328.76"), BigDecimal.ZERO));

    assertThat(service.view().lastError()).contains("322");
  }
}
