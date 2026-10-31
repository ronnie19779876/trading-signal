package org.jdkxx.trader.gateway.ibkr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.ib.client.Contract;
import com.ib.client.Decimal;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.gateway.RequestRejectedException;
import org.jdkxx.trader.gateway.ibkr.mapper.IbkrAccounts;
import org.junit.jupiter.api.Test;

/**
 * The standing subscription to the account-updates feed (3.4.6). Numbers taken from production
 * 2026-10-20 to 10-22: GOOG 162 shares after the add, positions-API average cost 346.806455
 * (pre-add), account-updates feed 339.52469445 (matching the IBKR app's 339.525).
 */
class IbkrPortfolioFeedTest {

  private static final String ACCT = "ACCT-A";
  private static final int GOOG = 208813720;

  private final IbkrLiveAccountTest.FakeWire wire = new IbkrLiveAccountTest.FakeWire();
  private final IbkrPortfolioFeed feed = new IbkrPortfolioFeed(wire, () -> ACCT);
  private int changes;

  {
    feed.onChange(() -> changes++);
  }

  private static IbkrAccounts.PortfolioRow row(
      int conId, String symbol, double qty, double avg, double unrealized) {
    Contract c = new Contract();
    c.conid(conId);
    c.symbol(symbol);
    return new IbkrAccounts.PortfolioRow(
        ACCT, c, Decimal.get(qty), avg, unrealized, 329.6, 53395.2);
  }

  private IbkrSubscriptions.Handler channel() {
    return wire.handlers.get(IbkrSubscriptions.PORTFOLIO);
  }

  @Test
  void 订阅发reqAccountUpdates_到齐之前不生效() {
    assertThat(feed.subscribe(false)).isTrue();
    verify(wire.client).reqAccountUpdates(true, ACCT);

    channel().item(row(GOOG, "GOOG", 162, 339.52469445, -1607.80));
    assertThat(feed.rows()).as("首批没到齐（accountDownloadEnd）之前不给，免得只有半批").isEmpty();

    channel().end();
    assertThat(feed.rows()).containsOnlyKeys(Integer.toString(GOOG));
    assertThat(changes).isEqualTo(1);
  }

  @Test
  void 到齐之后的变化攒批生效_清仓的移除() {
    feed.subscribe(false);
    channel().item(row(GOOG, "GOOG", 162, 339.52469445, -1607.80));
    channel().item(row(756733, "SPY", 210, 668.4270476, 21410.12));
    channel().end();

    channel().item(row(756733, "SPY", 0, 0, 0));
    assertThat(feed.rows()).as("攒批：300 ms 内不生效").containsKey("756733");
    wire.fire(Duration.ofMillis(IbkrPortfolioFeed.DEBOUNCE_MS));
    assertThat(feed.rows()).containsOnlyKeys(Integer.toString(GOOG));
  }

  /**
   * Kicked off by another client (2100 / 2101): clear, callers fall back to the original source,
   * and do not grab it back.
   */
  @Test
  void 被退订时清空并退回原值() {
    feed.subscribe(false);
    channel().item(row(GOOG, "GOOG", 162, 339.52469445, -1607.80));
    channel().end();

    channel()
        .error(
            new RequestRejectedException(
                Broker.IBKR, 2100, "API client has been unsubscribed from account data."));

    assertThat(feed.rows()).isEmpty();
    assertThat(feed.subscribed()).isFalse();
    assertThat(channel()).as("处理器已摘，后续推送不再进来").isNull();
    verify(wire.client, times(1))
        .reqAccountUpdates(anyBoolean(), anyString()); // did not re-subscribe on its own
  }

  @Test
  void 停止时退订并清空() {
    feed.subscribe(false);
    channel().item(row(GOOG, "GOOG", 162, 339.52469445, -1607.80));
    channel().end();

    feed.stop();

    verify(wire.client).reqAccountUpdates(false, ACCT);
    assertThat(feed.rows()).isEmpty();
    assertThat(channel()).as("先摘处理器：自己退订引起的 2100 不当成被挤掉").isNull();
  }

  @Test
  void 没配账户时不订阅() {
    IbkrPortfolioFeed noAccount = new IbkrPortfolioFeed(wire, () -> null);
    assertThat(noAccount.subscribe(false)).isFalse();
    verify(wire.client, never()).reqAccountUpdates(anyBoolean(), anyString());
  }

  /**
   * Regression (found as soon as 3.4.6 went live): production does not set TRADER_IBKR_ACCOUNT (the
   * account has always been picked from the managed list), and 3.4.6 only accepted the configured
   * value, so the feed was never subscribed. 3.4.7: without a configured value, use the gateway's
   * single managed account (provided by IbkrGateway); if that is still undecidable, the live
   * account passes in its account to subscribe later.
   */
  @Test
  void 账户定不下时不订_实时账户传入账户后订上() {
    IbkrPortfolioFeed unresolved = new IbkrPortfolioFeed(wire, () -> null);
    assertThat(unresolved.subscribe(false)).isFalse();
    verify(wire.client, never()).reqAccountUpdates(anyBoolean(), anyString());

    assertThat(unresolved.subscribe(false, ACCT)).isTrue();
    assertThat(wire.pending(IbkrPortfolioFeed.ACCOUNT_RETRY)).as("订上后撤掉待重试").isZero();
    verify(wire.client).reqAccountUpdates(true, ACCT);
    channel().item(row(GOOG, "GOOG", 162, 339.52469445, -1607.80));
    channel().end();
    assertThat(unresolved.rows()).as("推送按订阅时的账户过滤").containsOnlyKeys(Integer.toString(GOOG));
  }

  /**
   * Managed accounts arriving late (measured on a real gateway: the list is empty at the moment of
   * connecting): when undecidable, retry by itself 5 seconds later, without relying on someone
   * opening the live-account page; in production, with nobody watching the page, the feed must
   * still be subscribed before the 18:00 snapshot fetches positions.
   */
  @Test
  void 受管账户晚到时自动重试订上() {
    java.util.concurrent.atomic.AtomicReference<String> managed =
        new java.util.concurrent.atomic.AtomicReference<>();
    IbkrPortfolioFeed lazy = new IbkrPortfolioFeed(wire, managed::get);
    assertThat(lazy.subscribe(false)).isFalse();
    assertThat(wire.pending(IbkrPortfolioFeed.ACCOUNT_RETRY)).isEqualTo(1);

    managed.set(ACCT);
    wire.fire(IbkrPortfolioFeed.ACCOUNT_RETRY);

    verify(wire.client).reqAccountUpdates(true, ACCT);
    assertThat(wire.pending(IbkrPortfolioFeed.ACCOUNT_RETRY)).isZero();
  }

  @Test
  void 一直定不下账户时重试有上限() {
    IbkrPortfolioFeed never = new IbkrPortfolioFeed(wire, () -> null);
    never.subscribe(false);
    for (int i = 0; i < IbkrPortfolioFeed.ACCOUNT_RETRIES; i++) {
      wire.fire(IbkrPortfolioFeed.ACCOUNT_RETRY);
    }
    assertThat(wire.pending(IbkrPortfolioFeed.ACCOUNT_RETRY)).isZero();
    verify(wire.client, never()).reqAccountUpdates(anyBoolean(), anyString());
  }

  /**
   * No duplicate subscription while subscribed; on 1101 (same session, broker dropped the
   * subscription) cancel then re-subscribe, same as the account summary.
   */
  @Test
  void 已订着不重复订_连接恢复时退了重订() {
    feed.subscribe(false);
    feed.subscribe(false);
    verify(wire.client, times(1)).reqAccountUpdates(true, ACCT);

    feed.subscribe(true);
    verify(wire.client).reqAccountUpdates(false, ACCT);
    verify(wire.client, times(2)).reqAccountUpdates(true, ACCT);
  }

  /**
   * Average cost and unrealized are offered only when the quantity matches the positions API: while
   * a fill is half-propagated, prefer the original source.
   */
  @Test
  void 数量一致才给均价与浮盈() {
    feed.subscribe(false);
    channel().item(row(GOOG, "GOOG", 162, 339.52469445, -1607.80));
    channel().end();
    Set<String> logged = new HashSet<>();
    String goog = Integer.toString(GOOG);

    BigDecimal cost =
        IbkrPortfolioFeed.cost(
            feed.rows(),
            goog,
            new BigDecimal("162"),
            new BigDecimal("346.80645485"),
            "GOOG",
            logged);
    assertThat(cost).isEqualByComparingTo("339.52469445");
    assertThat(logged).as("两边均价不一致记一次 WARN").hasSize(1);
    IbkrPortfolioFeed.cost(
        feed.rows(), goog, new BigDecimal("162"), new BigDecimal("346.80645485"), "GOOG", logged);
    assertThat(logged).as("同一组数不重复记").hasSize(1);

    assertThat(IbkrPortfolioFeed.unrealized(feed.rows(), goog, new BigDecimal("162")))
        .isEqualByComparingTo("-1607.80");
    assertThat(
            IbkrPortfolioFeed.cost(
                feed.rows(),
                goog,
                new BigDecimal("62"),
                new BigDecimal("346.80645485"),
                "GOOG",
                logged))
        .as("数量不一致（加仓成交中途）不给")
        .isNull();
    assertThat(IbkrPortfolioFeed.unrealized(feed.rows(), "999", new BigDecimal("1"))).isNull();
  }
}
