package org.jdkxx.trader.gateway.ibkr;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.jdkxx.trader.gateway.RequestRejectedException;
import org.jdkxx.trader.gateway.ibkr.mapper.IbkrAccounts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The <b>standing subscription</b> to the account-updates feed (reqAccountUpdates, updatePortfolio;
 * 3.4.6, ARCHITECTURE §20.8): subscribed on connect, cancelled only on disconnect, it supplies
 * position <b>average cost and unrealized P&amp;L</b> from the same source as the IBKR app.
 *
 * <p>Why it is needed (measured in production 2026-10-20 to 10-22): after adding 100 GOOG shares
 * that day (62 to 162), the average cost from {@code reqPositionsMulti} and {@code reqPositions}
 * was still the pre-add 346.806, and {@code reqPnLSingle}'s unrealized P&amp;L was computed from it
 * too; the app showed 339.525. Only {@code updatePortfolio} gave 339.5247. After the gateway's next
 * daily restart {@code reqPositionsMulti} was corrected, but {@code reqPositions} and {@code
 * reqPnLSingle} <b>still were not</b>, so we cannot count on it fixing itself.
 *
 * <p>Constraints:
 *
 * <ul>
 *   <li><b>Only one API client may subscribe to a given account at a time</b> (IBKR docs; a second
 *       one kicks the first off, and the one kicked off receives 2100 / 2101). Hence it is enabled
 *       on the production instance only ({@code trader.environment=PROD}); dev instances and ad-hoc
 *       probes must never subscribe to it;
 *   <li>the first batch completes in about 0.3 seconds ({@code accountDownloadEnd}); after that a
 *       batch is pushed on changes or <b>roughly every 3 minutes</b> (measured 09-19), so after an
 *       add the cost updates up to 3 minutes late;
 *   <li>unrealized P&amp;L uses IBKR's own mark (a few cents off the market-data lastPrice) and is
 *       used as is, never recomputed.
 * </ul>
 *
 * Cost and unrealized P&amp;L are offered only when the <b>quantity matches the positions API</b>
 * (while a fill is half-propagated and the two disagree, the original source is preferred). On
 * unsubscribe / taken by another client (2100 / 2101) everything is cleared and callers fall back
 * to the original source automatically. <b>Every method is called on the dispatch thread</b>; only
 * {@link #rows()} may be read from other threads (an immutable snapshot).
 */
final class IbkrPortfolioFeed {

  private static final Logger log = LoggerFactory.getLogger(IbkrPortfolioFeed.class);

  /**
   * Within a batch rows arrive one position at a time; collect them before publishing (same
   * approach as the account summary).
   */
  static final long DEBOUNCE_MS = 300;

  /**
   * How often and how many times to retry when the account cannot be decided yet (3.4.7): right
   * after connecting the managed-account list has often not arrived (measured on a real gateway:
   * empty at the moment of connecting), and in production, with nobody opening the page, no live
   * account comes along to subscribe later, yet the feed must be subscribed by the time the 18:00
   * snapshot fetches positions.
   */
  static final Duration ACCOUNT_RETRY = Duration.ofSeconds(5);

  static final int ACCOUNT_RETRIES = 12;

  private final IbkrLiveAccount.Wire wire;

  /**
   * Which account to subscribe to: the configured {@code trader.ibkr.account}, otherwise the
   * managed account reported by the gateway (when there is exactly one). 3.4.6 only accepted the
   * configured value, but production's {@code TRADER_IBKR_ACCOUNT} has always been empty (the
   * account is picked from the managed list), so after release it never subscribed (fixed in
   * 3.4.7).
   */
  private final java.util.function.Supplier<String> account;

  /** The account of the current subscription (pushed rows are filtered by it) */
  private String accountId;

  /** The session in which we subscribed; null = no subscription right now */
  private Object bound;

  private boolean ready;
  private Runnable flush;
  private Runnable onChange = () -> {};
  private final Map<String, IbkrAccounts.PortfolioRow> pending = new LinkedHashMap<>();
  private volatile Map<String, IbkrAccounts.PortfolioRow> published = Map.of();

  /** The average-cost mismatch WARN is logged only once per combination of numbers */
  private final Set<String> costDiffLogged = new HashSet<>();

  private boolean noAccountLogged;
  private int accountRetries;
  private Runnable accountRetry;
  private String lastError;

  IbkrPortfolioFeed(IbkrLiveAccount.Wire wire, java.util.function.Supplier<String> account) {
    this.wire = wire;
    this.account = account;
  }

  /**
   * Called after each batch is published (the live account then re-emits positions and per-position
   * P&amp;L).
   */
  void onChange(Runnable r) {
    this.onChange = r == null ? () -> {} : r;
  }

  boolean subscribed() {
    return bound != null && bound == wire.sessionToken();
  }

  String lastError() {
    return lastError;
  }

  /**
   * The most recently published batch (conId to row); empty when not subscribed, not complete yet,
   * or unsubscribed. Safe to read from any thread.
   */
  Map<String, IbkrAccounts.PortfolioRow> rows() {
    return published;
  }

  /**
   * @param afterRecovery the connection has just recovered (reconnect or 1101): on 1101 the session
   *     is unchanged while the broker has dropped the subscription, so it must be re-subscribed
   *     (same as the account summary).
   */
  boolean subscribe(boolean afterRecovery) {
    return subscribe(afterRecovery, null);
  }

  /**
   * @param fallbackAccount the account to use while it cannot be decided otherwise (the live
   *     account passes its own account when it subscribes); nullable
   */
  boolean subscribe(boolean afterRecovery, String fallbackAccount) {
    String acct = account.get();
    if (acct == null || acct.isBlank()) {
      acct = fallbackAccount;
    }
    if (acct == null || acct.isBlank()) {
      if (wire.sessionToken() != null && accountRetry == null && accountRetries < ACCOUNT_RETRIES) {
        accountRetries++;
        accountRetry =
            wire.later(
                ACCOUNT_RETRY,
                () -> {
                  accountRetry = null;
                  subscribe(afterRecovery, fallbackAccount);
                });
      } else if (accountRetries >= ACCOUNT_RETRIES && !noAccountLogged) {
        log.warn(
            "账户更新推送定不下账户（没配 trader.ibkr.account，受管账户不止一个或一直没收到），不订阅；"
                + "实时账户订阅时会用它的账户再试。持仓均价与浮盈暂用原来的来源");
        noAccountLogged = true;
      }
      return false;
    }
    accountRetries = 0;
    if (accountRetry != null) {
      accountRetry.run();
      accountRetry = null;
    }
    Object current = wire.sessionToken();
    if (current == null) {
      return false;
    }
    if (subscribed() && !afterRecovery) {
      return true;
    }
    if (subscribed()) {
      log.info("连接恢复（会话未变，多半是 1101 数据丢失）：重订账户更新推送");
      cancelOnBroker();
    }
    clear();
    bound = current;
    accountId = acct.trim();
    noAccountLogged = false;
    String subscribeAccount = accountId;
    wire.open(IbkrSubscriptions.PORTFOLIO, handler());
    if (!wire.subscribe("账户更新推送", c -> c.reqAccountUpdates(true, subscribeAccount))) {
      wire.close(IbkrSubscriptions.PORTFOLIO);
      bound = null;
      return false;
    }
    return true;
  }

  /** Cancels on disconnect / shutdown. */
  void stop() {
    if (accountRetry != null) {
      accountRetry.run();
      accountRetry = null;
    }
    accountRetries = 0;
    if (subscribed()) {
      cancelOnBroker();
    } else {
      wire.close(IbkrSubscriptions.PORTFOLIO);
    }
    bound = null;
    clear();
    publish();
  }

  /**
   * This position's average cost from the feed, <b>only when the quantity matches the positions
   * API</b>, otherwise null (use the original source). When the two averages differ a WARN is
   * logged (once per combination of numbers) to collect samples of when the positions API lags and
   * when it corrects itself.
   */
  static BigDecimal cost(
      Map<String, IbkrAccounts.PortfolioRow> rows,
      String conId,
      BigDecimal quantity,
      BigDecimal positionsCost,
      String symbol,
      Set<String> logged) {
    IbkrAccounts.PortfolioRow r = matching(rows, conId, quantity);
    if (r == null) {
      return null;
    }
    BigDecimal feed = IbkrAccounts.amount(r.averageCost());
    if (feed != null
        && positionsCost != null
        && feed.subtract(positionsCost).abs().compareTo(new BigDecimal("0.000001")) > 0) {
      String key = conId + "|" + positionsCost.toPlainString() + "|" + feed.toPlainString();
      if (logged.add(key)) {
        log.warn(
            "持仓接口与账户更新推送的均价不一致（{}，数量 {}）：持仓接口 {}，账户更新推送 {}；采用后者（与盈透 App 同源）",
            symbol,
            quantity.stripTrailingZeros().toPlainString(),
            positionsCost.toPlainString(),
            feed.toPlainString());
      }
    }
    return feed;
  }

  /**
   * This position's average cost from the feed (no logging, used for reconciliation); only when the
   * quantity matches.
   */
  static Double averageCost(
      Map<String, IbkrAccounts.PortfolioRow> rows, String conId, BigDecimal quantity) {
    IbkrAccounts.PortfolioRow r = matching(rows, conId, quantity);
    BigDecimal v = r == null ? null : IbkrAccounts.amount(r.averageCost());
    return v == null ? null : v.doubleValue();
  }

  /** This position's unrealized P&amp;L from the feed; likewise only when the quantity matches. */
  static BigDecimal unrealized(
      Map<String, IbkrAccounts.PortfolioRow> rows, String conId, BigDecimal quantity) {
    IbkrAccounts.PortfolioRow r = matching(rows, conId, quantity);
    return r == null ? null : IbkrAccounts.amount(r.unrealized());
  }

  Set<String> costDiffLogged() {
    return costDiffLogged;
  }

  private static IbkrAccounts.PortfolioRow matching(
      Map<String, IbkrAccounts.PortfolioRow> rows, String conId, BigDecimal quantity) {
    IbkrAccounts.PortfolioRow r = rows.get(conId);
    if (r == null || quantity == null || r.position() == null || !r.position().isValid()) {
      return null;
    }
    return r.position().value().compareTo(quantity) == 0 ? r : null;
  }

  private void cancelOnBroker() {
    wire.close(
        IbkrSubscriptions
            // detach the handler first: a 2100 caused by our own unsubscribe must not count as
            // being kicked off
            .PORTFOLIO);
    String acct = accountId;
    wire.unsubscribe("账户更新推送", c -> c.reqAccountUpdates(false, acct));
  }

  private void clear() {
    pending.clear();
    ready = false;
    if (flush != null) {
      flush.run();
      flush = null;
    }
  }

  private IbkrSubscriptions.Handler handler() {
    return new IbkrSubscriptions.Handler() {
      @Override
      public void item(Object item) {
        IbkrAccounts.PortfolioRow r = (IbkrAccounts.PortfolioRow) item;
        if (r.contract() == null || accountId == null || !accountId.equals(r.account())) {
          return;
        }
        String conId = Integer.toString(r.contract().conid());
        if (r.position() == null || !r.position().isValid() || r.position().value().signum() == 0) {
          pending.remove(conId); // position closed (or invalid quantity): no longer offered
        } else {
          pending.put(conId, r);
        }
        if (ready && flush == null) {
          flush = wire.later(Duration.ofMillis(DEBOUNCE_MS), IbkrPortfolioFeed.this::flushNow);
        }
      }

      @Override
      public void end() {
        ready = true;
        lastError = null;
        if (flush != null) {
          flush.run();
          flush = null;
        }
        flushNow();
      }

      @Override
      public void error(RequestRejectedException e) {
        lastError = e.getMessage();
        log.warn(
            "账户更新推送失效（{}）：持仓均价与浮盈退回持仓接口 / 逐只盈亏的原值，下次连上再订。" + "若不是本实例自己退订，多半是有别的 API 客户端订了同一账户",
            e.getMessage());
        bound = null;
        wire.close(IbkrSubscriptions.PORTFOLIO);
        clear();
        publish();
      }
    };
  }

  private void flushNow() {
    flush = null;
    publish();
  }

  private void publish() {
    published = ready ? Map.copyOf(pending) : Map.of();
    try {
      onChange.run();
    } catch (RuntimeException e) {
      log.warn("账户更新推送生效后回调出错：{}", e.toString());
    }
  }
}
