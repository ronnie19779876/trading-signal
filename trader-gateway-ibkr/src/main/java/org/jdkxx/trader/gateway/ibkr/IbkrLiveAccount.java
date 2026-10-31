package org.jdkxx.trader.gateway.ibkr;

import com.ib.client.Contract;
import com.ib.client.EClientSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.jdkxx.trader.domain.Position;
import org.jdkxx.trader.domain.PositionPnl;
import org.jdkxx.trader.gateway.LiveAccountListener;
import org.jdkxx.trader.gateway.RequestRejectedException;
import org.jdkxx.trader.gateway.ibkr.mapper.IbkrAccounts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The live subscriptions of one account (since 3.0.2): standing account summary, account P&amp;L
 * and positions, plus one per-position P&amp;L and one market-data line (for the last price) per
 * held position.
 *
 * <p><b>Every method runs on the dispatch thread</b> ({@link IbkrGateway} hands calls over, and
 * subscription callbacks are moved to dispatch by {@link IbkrSubscriptions}), so the state is not
 * locked.
 *
 * <p>Measured basis (read-only probe, 2026-09-19):
 *
 * <ul>
 *   <li>the account summary's first batch completes in about 0.3 seconds, after which values are
 *       pushed <b>one by one</b> with no further End, roughly one batch every 3 minutes, so the
 *       rows are collected and sent once after {@value IbkrAccountSummaryFeed#DEBOUNCE_MS} ms of
 *       silence;
 *   <li>whether a reqPnL push is right <b>cannot be judged by its position in the sequence</b>
 *       (three measurements on 09-19): right after connecting the first push covered only one
 *       position and the second, 1 second later, was right; unsubscribing and re-subscribing within
 *       a dozen seconds of the per-position P&amp;L subscriptions also gave a wrong first push;
 *       re-subscribing once per-position P&amp;L had been running steadily produced <b>a single
 *       push, and a correct one</b>, with no second push while prices stood still. A correct push
 *       equals the sum of the per-position daily / unrealized values exactly, a wrong one is far
 *       off, so it is <b>verified against the per-position sum</b>: adopted only when it matches
 *       (the sum only decides completeness; what is shown is still the raw reqPnL value). Once one
 *       push has been adopted, later pushes pass straight through; while per-position rows are
 *       still missing the push is held and verified once they are complete. If nothing has passed
 *       after {@value #PNL_WATCHDOG_SECONDS} seconds it re-subscribes, at most {@value
 *       #PNL_MAX_RETRIES} times;
 *   <li>market data for held instruments (reqMktData) supplies lastPrice and the previous close
 *       (CLOSE): the account has real-time permissions; after the close bid/ask are -1; the last
 *       price is not "market value / quantity" (GOOG 346.08 vs 344.41); compared with an IBKR app
 *       screenshot (09-19): last 346.08 and previous close 343.68 agree;
 *   <li>after the positions End, fills push changed rows, and quantity 0 means closed, so the full
 *       list is maintained, a complete list is emitted on every change, and the per-position
 *       P&amp;L subscriptions follow it.
 * </ul>
 *
 * On 1101 (connection restored, subscription data lost) the session is unchanged and the old
 * subscriptions still hang on the gateway: cancel first, then re-subscribe, otherwise the account
 * summary would hit the 2-per-client limit (322). <b>The account summary does not live here</b>: it
 * became a standing subscription made once per connection cycle ({@link IbkrAccountSummaryFeed},
 * 3.0.9), shared by the live account and the daily snapshot. See that class for why: IBKR does not
 * release slots on cancel, so subscribing and cancelling repeatedly hits 322 sooner or later.
 *
 * <p>How the account P&amp;L check evolved: 3.0.10 subtracted realized (disproved on 10-09), 3.1.5
 * compared only unrealized on closing days, 3.4.6 compares only daily when nothing was closed, and
 * 3.5.1 corrects the per-position unrealized with the feed's new average cost when comparing
 * unrealized (a close and an add on the same day), and 3.5.2 derives that correction from
 * reqPnLSingle's value instead of the positions API's cost; see {@link #verifyPnl()}.
 *
 * <p><b>Position average cost and per-position unrealized</b> (3.4.6): when the account-updates
 * feed is available ({@link IbkrPortfolioFeed}, production instance only) and the quantity matches,
 * its values are used, the same source as the IBKR app; daily and market value still come from
 * reqPnLSingle, the price still from market data.
 */
final class IbkrLiveAccount {

  private static final Logger log = LoggerFactory.getLogger(IbkrLiveAccount.class);
  static final long PNL_WATCHDOG_SECONDS = 10;
  static final int PNL_MAX_RETRIES = 3;

  /**
   * Verification tolerance: a correct push measured exactly equal to the per-position sum, wrong
   * ones were off by tens to thousands; a little slack covers intraday jitter when the two sides
   * are not sampled at the same instant.
   */
  static final double PNL_TOLERANCE = 1.0;

  /** Seam to the connection: {@link IbkrConnection} in production, a fake in unit tests. */
  interface Wire {
    Object sessionToken();

    int nextId();

    boolean subscribe(String what, Consumer<EClientSocket> request);

    void unsubscribe(String what, Consumer<EClientSocket> cancel);

    void open(int id, IbkrSubscriptions.Handler handler);

    void close(int id);

    /** Delayed task: runs on the dispatch thread when due. Returns a Runnable that cancels it. */
    Runnable later(Duration delay, Runnable task);

    Instant now();
  }

  private record Sub(int id, String what, Consumer<EClientSocket> cancel) {}

  final String accountId;
  private LiveAccountListener listener;
  private final Wire wire;

  /**
   * Account-updates feed (3.4.6, production instance only): position average cost and unrealized
   * prefer it; when null or without a matching row the original source is used.
   */
  private IbkrPortfolioFeed portfolio;

  /**
   * The session in which we subscribed ({@link Wire#sessionToken()}); null = no subscription right
   * now
   */
  private Object bound;

  private Sub positionsSub;
  private Sub pnlSub;
  private final Map<String, Sub> singles = new LinkedHashMap<>();
  private final Map<String, Sub> quotes = new LinkedHashMap<>();
  private final Map<String, Boolean> delayed = new LinkedHashMap<>();

  /**
   * Latest last price and previous close per position: they arrive as two separate ticks and are
   * emitted together.
   */
  private final Map<String, java.math.BigDecimal[]> lastAndClose = new LinkedHashMap<>();

  private final Map<String, IbkrAccounts.PositionRow> positions = new LinkedHashMap<>();
  private boolean positionsReady;
  private boolean pnlValid;

  /** The most recent account P&amp;L push that has not passed verification yet */
  private IbkrAccounts.PnlRow pnlPending;

  /** Latest per-position P&amp;L for each position: used to verify the account P&amp;L */
  private final Map<String, IbkrAccounts.PnlSingleRow> singleRows = new LinkedHashMap<>();

  private int pnlRetries;
  private Runnable pnlWatchdog;

  /**
   * The verification-failure log is written once per subscription, so a push every 5 seconds does
   * not flood the log.
   */
  private boolean pnlMismatchLogged;

  private boolean pnlDailySkipLogged;

  IbkrLiveAccount(String accountId, LiveAccountListener listener, Wire wire) {
    this.accountId = accountId;
    this.listener = listener;
    this.wire = wire;
  }

  void listener(LiveAccountListener listener) {
    this.listener = listener;
  }

  void portfolio(IbkrPortfolioFeed portfolio) {
    this.portfolio = portfolio;
  }

  /**
   * A new batch of the account-updates feed took effect: positions (average cost) and per-position
   * P&amp;L (unrealized) are re-emitted with the new values. The feed sends a batch about every 3
   * minutes, so after an add the cost on the page updates up to that much later.
   */
  void onPortfolioChanged() {
    if (bound == null || !positionsReady) {
      return;
    }
    emitPositions();
    for (Map.Entry<String, IbkrAccounts.PnlSingleRow> e : singleRows.entrySet()) {
      listener.onPositionPnl(positionPnl(e.getKey(), e.getValue()));
    }
  }

  private Map<String, IbkrAccounts.PortfolioRow> portfolioRows() {
    return portfolio == null ? Map.of() : portfolio.rows();
  }

  /**
   * Replaces the average cost with the feed's value (when the quantity matches), otherwise
   * unchanged.
   */
  private List<Position> withPortfolioCost(List<Position> list) {
    Map<String, IbkrAccounts.PortfolioRow> rows = portfolioRows();
    if (rows.isEmpty()) {
      return list;
    }
    List<Position> out = new ArrayList<>(list.size());
    for (Position p : list) {
      java.math.BigDecimal cost =
          IbkrPortfolioFeed.cost(
              rows,
              p.brokerRef(),
              p.quantity(),
              p.averageCost(),
              p.symbol(),
              portfolio.costDiffLogged());
      out.add(
          cost == null
              ? p
              : new Position(
                  p.broker(),
                  p.accountId(),
                  p.brokerRef(),
                  p.symbol(),
                  p.localSymbol(),
                  p.securityType(),
                  p.exchange(),
                  p.primaryExchange(),
                  p.currency(),
                  p.quantity(),
                  cost));
    }
    return List.copyOf(out);
  }

  /**
   * Per-position P&amp;L: unrealized replaced by the feed's value (when the quantity matches);
   * daily and market value stay reqPnLSingle's raw values.
   */
  private PositionPnl positionPnl(String conId, IbkrAccounts.PnlSingleRow row) {
    PositionPnl raw = IbkrAccounts.positionPnl(conId, row, wire.now());
    java.math.BigDecimal unrealized =
        IbkrPortfolioFeed.unrealized(portfolioRows(), conId, raw.quantity());
    return unrealized == null
        ? raw
        : new PositionPnl(
            raw.broker(),
            raw.brokerRef(),
            raw.receivedAt(),
            raw.quantity(),
            raw.daily(),
            unrealized,
            raw.realized(),
            raw.marketValue());
  }

  boolean subscribed() {
    return bound != null;
  }

  /**
   * Subscribe (or re-subscribe). Does nothing when not connected and returns false; call again once
   * connected.
   */
  boolean subscribe() {
    Object current = wire.sessionToken();
    if (bound != null && bound == current) {
      cancelAll(); // same session (1101): the old subscriptions are still alive, cancel them first
    } else {
      forgetAll(); // the old session has ended and its subscriptions with it: just drop the records
    }
    bound = current;
    positionsReady = false;
    pnlValid = false;
    pnlPending = null;
    pnlRetries = 0;

    positionsSub =
        open(
            "实时持仓",
            positionsHandler(),
            id -> c -> c.reqPositionsMulti(id, accountId, ""),
            id -> c -> c.cancelPositionsMulti(id));
    pnlSub = openPnl();
    if (positionsSub == null || pnlSub == null) {
      // dropped halfway through: cancel what was sent and re-subscribe the whole set on the next
      // connect
      cancelAll();
      bound = null;
      return false;
    }
    schedulePnlWatchdog();
    return true;
  }

  private Sub openPnl() {
    pnlPending = null;
    return open(
        "实时账户盈亏", pnlHandler(), id -> c -> c.reqPnL(id, accountId, ""), id -> c -> c.cancelPnL(id));
  }

  private void schedulePnlWatchdog() {
    pnlWatchdog = wire.later(Duration.ofSeconds(PNL_WATCHDOG_SECONDS), this::checkPnl);
  }

  /**
   * The account P&amp;L still has no valid value (first push wrong, no second push coming):
   * unsubscribe and subscribe again, at most {@value #PNL_MAX_RETRIES} times. reqPnL is not subject
   * to the account-summary slot limit.
   */
  void checkPnl() {
    pnlWatchdog = null;
    if (bound == null || pnlValid || pnlRetries >= PNL_MAX_RETRIES) {
      return;
    }
    pnlRetries++;
    log.info("实时账户盈亏 {} 秒没有有效推送，重订（第 {} 次）", PNL_WATCHDOG_SECONDS, pnlRetries);
    cancel(pnlSub);
    pnlSub = openPnl();
    if (pnlSub != null && pnlRetries < PNL_MAX_RETRIES) {
      schedulePnlWatchdog();
    }
  }

  /** Cancel everything. */
  void stop() {
    if (bound != null && bound == wire.sessionToken()) {
      cancelAll();
    } else {
      forgetAll();
    }
    bound = null;
  }

  int singleCount() {
    return singles.size();
  }

  int quoteCount() {
    return quotes.size();
  }

  private Sub open(
      String what,
      IbkrSubscriptions.Handler handler,
      java.util.function.IntFunction<Consumer<EClientSocket>> request,
      java.util.function.IntFunction<Consumer<EClientSocket>> cancel) {
    int id = wire.nextId();
    wire.open(id, handler);
    if (!wire.subscribe(what, request.apply(id))) {
      wire.close(id);
      return null;
    }
    return new Sub(id, what, cancel.apply(id));
  }

  private void cancel(Sub sub) {
    if (sub != null) {
      wire.close(sub.id());
      wire.unsubscribe(sub.what(), sub.cancel());
    }
  }

  private void cancelAll() {
    cancel(positionsSub);
    cancel(pnlSub);
    singles.values().forEach(this::cancel);
    quotes.values().forEach(this::cancel);
    clearState();
  }

  private void forgetAll() {
    for (Sub s : allSubs()) {
      wire.close(s.id());
    }
    clearState();
  }

  private List<Sub> allSubs() {
    List<Sub> all = new ArrayList<>(singles.values());
    all.addAll(quotes.values());
    for (Sub s : new Sub[] {positionsSub, pnlSub}) {
      if (s != null) {
        all.add(s);
      }
    }
    return all;
  }

  private void clearState() {
    positionsSub = pnlSub = null;
    singles.clear();
    quotes.clear();
    delayed.clear();
    lastAndClose.clear();
    singleRows.clear();
    pnlPending = null;
    pnlMismatchLogged = false;
    pnlDailySkipLogged = false;
    if (pnlWatchdog != null) {
      pnlWatchdog.run(); // cancels it
      pnlWatchdog = null;
    }
    positions.clear();
    positionsReady = false;
  }

  // ------------------------------------------------------------------ positions

  private IbkrSubscriptions.Handler positionsHandler() {
    return new IbkrSubscriptions.Handler() {
      @Override
      public void item(Object item) {
        IbkrAccounts.PositionRow r = (IbkrAccounts.PositionRow) item;
        if (r.contract() == null || !accountId.equals(r.account())) {
          return;
        }
        String conId = Integer.toString(r.contract().conid());
        if (r.position() == null || !r.position().isValid()) {
          log.warn("实时持仓收到无效数量（{}），忽略这一条", r.contract().symbol());
          return;
        }
        if (r.position().value().signum() == 0) {
          positions.remove(conId); // fully closed
        } else {
          positions.put(conId, r);
        }
        if (positionsReady) {
          emitPositions();
        }
      }

      @Override
      public void end() {
        positionsReady = true;
        emitPositions();
      }

      @Override
      public void error(RequestRejectedException e) {
        listener.onLiveError("实时持仓", e);
      }
    };
  }

  private void emitPositions() {
    try {
      List<Position> list =
          withPortfolioCost(IbkrAccounts.positions(accountId, new ArrayList<>(positions.values())));
      listener.onPositions(list);
    } catch (RuntimeException e) {
      log.warn("实时持仓映射失败：{}", e.toString());
    }
    syncSingles();
  }

  /**
   * Per-position P&amp;L and market-data subscriptions follow the positions: subscribe for new
   * ones, cancel for closed ones.
   */
  private void syncSingles() {
    for (String conId : new ArrayList<>(singles.keySet())) {
      if (!positions.containsKey(conId)) {
        cancel(singles.remove(conId));
        singleRows.remove(conId);
      }
    }
    for (String conId : new ArrayList<>(quotes.keySet())) {
      if (!positions.containsKey(conId)) {
        cancel(quotes.remove(conId));
        delayed.remove(conId);
        lastAndClose.remove(conId);
      }
    }
    for (Map.Entry<String, IbkrAccounts.PositionRow> e : positions.entrySet()) {
      String conId = e.getKey();
      int con = Integer.parseInt(conId);
      if (!singles.containsKey(conId)) {
        Sub s =
            open(
                "逐只盈亏 " + conId,
                singleHandler(conId),
                id -> c -> c.reqPnLSingle(id, accountId, "", con),
                id -> c -> c.cancelPnLSingle(id));
        if (s != null) {
          singles.put(conId, s);
        }
      }
      if (!quotes.containsKey(conId)) {
        Contract src = e.getValue().contract();
        Contract contract = new Contract();
        contract.conid(con);
        contract.exchange("SMART");
        contract.secType(src.getSecType());
        contract.currency(src.currency());
        Sub q =
            open(
                "持仓行情 " + conId,
                quoteHandler(conId),
                id -> c -> c.reqMktData(id, contract, "", false, false, null),
                id -> c -> c.cancelMktData(id));
        if (q != null) {
          quotes.put(conId, q);
        }
      }
    }
  }

  private IbkrSubscriptions.Handler quoteHandler(String conId) {
    return new IbkrSubscriptions.Handler() {
      @Override
      public void item(Object item) {
        if (item instanceof IbkrAccounts.MarketDataTypeRow t) {
          delayed.put(conId, t.type() == 3 || t.type() == 4);
        } else if (item instanceof IbkrAccounts.TickRow t) {
          boolean last = IbkrAccounts.isLast(t.field());
          if (!last && !IbkrAccounts.isClose(t.field())) {
            return; // bid/ask, open/high/low and the like are not needed
          }
          java.math.BigDecimal price = IbkrAccounts.price(t.price());
          if (price == null) {
            return;
          }
          java.math.BigDecimal[] lc =
              lastAndClose.computeIfAbsent(conId, k -> new java.math.BigDecimal[2]);
          lc[last ? 0 : 1] = price;
          boolean isDelayed =
              delayed.getOrDefault(conId, false)
                  || t.field() == IbkrAccounts.TICK_DELAYED_LAST
                  || t.field() == IbkrAccounts.TICK_DELAYED_CLOSE;
          listener.onPositionPrice(
              new org.jdkxx.trader.domain.PositionPrice(
                  org.jdkxx.trader.domain.Broker.IBKR, conId, wire.now(), lc[0], lc[1], isDelayed));
        }
      }

      @Override
      public void error(RequestRejectedException e) {
        listener.onLiveError("持仓行情", e);
      }
    };
  }

  private IbkrSubscriptions.Handler singleHandler(String conId) {
    return new IbkrSubscriptions.Handler() {
      @Override
      public void item(Object item) {
        IbkrAccounts.PnlSingleRow row = (IbkrAccounts.PnlSingleRow) item;
        singleRows.put(conId, row);
        listener.onPositionPnl(positionPnl(conId, row));
        if (!pnlValid && pnlPending != null) {
          // account P&L arrived first and per-position rows later: verify once complete
          verifyPnl();
        }
      }

      @Override
      public void error(RequestRejectedException e) {
        listener.onLiveError("逐只盈亏", e);
      }
    };
  }

  // ------------------------------------------------------------------ account summary

  // ------------------------------------------------------------------ account P&L

  private IbkrSubscriptions.Handler pnlHandler() {
    return new IbkrSubscriptions.Handler() {
      @Override
      public void item(Object item) {
        IbkrAccounts.PnlRow row = (IbkrAccounts.PnlRow) item;
        if (pnlValid) {
          listener.onPnl(IbkrAccounts.pnl(row, wire.now()));
          return;
        }
        pnlPending = row;
        verifyPnl();
      }

      @Override
      public void error(RequestRejectedException e) {
        listener.onLiveError("实时账户盈亏", e);
      }
    };
  }

  /**
   * Verifies the held account P&amp;L push: the per-position P&amp;L must cover every position;
   * <b>when nothing was closed today the daily sums are compared, when something was closed the
   * unrealized sums are</b> (tolerance {@value #PNL_TOLERANCE}, since 3.4.6). The per-position sum
   * only decides whether this push is complete; what is emitted is still the account P&amp;L's raw
   * value.
   *
   * <h2>Why unrealized is no longer compared when nothing was closed (measured 2026-10-20 to 10-22)
   * </h2>
   *
   * After adding 100 GOOG shares that day, {@code reqPnLSingle}'s unrealized was still computed
   * from the pre-add average cost 346.806 (the app and the account-updates feed had 339.525), so
   * the account-level unrealized and the per-position sum differed by a constant 1,179.65 (= 100
   * &times; 11.80) and the account P&amp;L stayed blank all day; it was still so after the
   * gateway's next-day daily restart. Daily, meanwhile, matched exactly on both sides (difference
   * 0.0), and an incomplete first push still fails on daily (measured 10-22: off by 48.74), so the
   * check loses none of its power. The comparison still uses {@code reqPnLSingle}'s raw values
   * (both sides are streaming pushes and can be compared exactly); the per-position unrealized
   * shown on the page comes from the account-updates feed instead.
   *
   * <h2>Why daily cannot be compared on a closing day (measured 2026-10-09, overturning 3.0.10)
   * </h2>
   *
   * The real pushes after selling 80 BRK.B shares that day:
   *
   * <pre>
   * account          daily=1093.3018226278286  unrealized=26615.850201798563  realized=477.157432
   * per-position sum daily=1099.14422262783    unrealized=26615.850201798563
   * </pre>
   *
   * <ul>
   *   <li><b>unrealized difference = 0.0</b>: both sides only include open positions, so they match
   *       exactly even on a closing day; that is the clean completeness criterion;
   *   <li><b>daily difference = &minus;5.8424</b>, while {@code realized = 477.157432}. After
   *       3.0.10 subtracted realized the difference became <b>&minus;483.00</b>: <b>the wrong sign
   *       and 82 times larger</b>;
   *   <li>the account's daily contains the closed instrument's <b>change for the day</b> (sale
   *       price against the previous close, including commission), <b>not</b> its lifetime realized
   *       gain against cost. Evidence: BRK.B's previous-close value 80 &times; 511.05 = 40,884.00,
   *       the cash received from the sale 40,878.16, difference <b>5.84</b>; two log lines 40
   *       minutes apart showed the identical difference &minus;482.99983200000133 (a fixed amount,
   *       not drift over time).
   * </ul>
   *
   * <b>The correct adjustment is not available to us</b>: once closed, an instrument disappears
   * from {@code reqPositionsMulti}, and {@code reqPnLSingle}'s realized is always {@code
   * Double.MAX_VALUE}. Widening the tolerance does not work either: a closed instrument's change
   * for the day is unbounded (it was only 5.84 today because the sale was almost at the previous
   * close). So on closing days daily is <b>not compared</b> and unrealized alone guards
   * completeness; daily is still shown as IBKR's raw value.
   *
   * <p><b>n = 1</b>: one trading day, one closed instrument, two samples. To be re-checked on the
   * next closing day.
   */
  private void verifyPnl() {
    IbkrAccounts.PnlRow row = pnlPending;
    if (row == null || !positionsReady || !singleRows.keySet().containsAll(positions.keySet())) {
      return;
    }
    double daily = 0;
    double unreal = 0;
    double correction = 0;
    for (String conId : positions.keySet()) {
      IbkrAccounts.PnlSingleRow r = singleRows.get(conId);
      if (IbkrAccounts.amount(r.daily()) == null || IbkrAccounts.amount(r.unrealized()) == null) {
        return;
      }
      daily += r.daily();
      // On the day of an add, reqPnLSingle's unrealized uses the pre-add average cost (3.4.6);
      // replace that cost with the feed's, keeping reqPnLSingle's own price basis (see
      // correctedUnrealized). On days without an add the correction is 0.
      double corrected = correctedUnrealized(conId, r);
      unreal += corrected;
      correction += r.unrealized() - corrected;
    }
    // Was anything closed today: yes if the account-level realized is non-zero (per-position
    // realized is always Double.MAX_VALUE and unusable). When realized is unavailable this cannot
    // be decided, so it is treated as "closed": compare unrealized only, better one check fewer
    // than a daily P&L left blank all day on a closing day.
    java.math.BigDecimal realizedAmount = IbkrAccounts.amount(row.realized());
    Double realized = realizedAmount == null ? null : realizedAmount.doubleValue();
    boolean closedToday = realized == null || realized != 0;

    // Nothing closed: compare daily only (3.4.6). On an add day reqPnLSingle's unrealized uses the
    // pre-add cost and differs from the account figure by a fixed amount (2026-10-20 GOOG: a
    // constant 1,179.65, not fixed by the gateway's next-day restart either), whereas daily matches
    // exactly on both sides (difference 0.0); an incomplete first push still fails on daily
    // (measured 10-22: off by 48.74), so the check loses nothing.
    // Closing day: daily is not comparable (see above), only unrealized is; when there was also an
    // add that day, the per-position unrealized has already been corrected with the feed's average
    // cost above (3.5.1; derived from reqPnLSingle's value since 3.5.2).
    // Account-level values are used raw: a NaN or MAX_VALUE there simply fails the comparison (fail
    // closed).
    boolean unrealOk = !closedToday || Math.abs(row.unrealized() - unreal) <= PNL_TOLERANCE;
    boolean dailyOk = closedToday || Math.abs(row.daily() - daily) <= PNL_TOLERANCE;

    if (unrealOk && dailyOk) {
      pnlValid = true;
      pnlPending = null;
      pnlMismatchLogged = false;
      if (closedToday) {
        logDailyNotChecked(row, realized, daily);
      }
      listener.onPnl(IbkrAccounts.pnl(row, wire.now()));
      return;
    }
    logPnlMismatch(row, realized, daily, unreal, correction, closedToday);
  }

  /**
   * The per-position unrealized used for verification: value &minus; feed average cost &times;
   * quantity when the account-updates feed has this position with the same quantity and
   * reqPnLSingle's value is set; otherwise reqPnLSingle's raw unrealized.
   *
   * <p>Why value rather than the positions API's cost (found in the 2026-10-31 review): on an add
   * day reqPnLSingle computes unrealized from the pre-add cost, so value &minus; unrealized =
   * quantity &times; stale cost (09-19 probe, GOOG: 21,353.42 &minus; (&minus;148.58) = 21,502.00 =
   * 62 &times; 346.806455). 3.5.1 subtracted (feed average &minus; positions-API average) &times;
   * quantity, which only works while reqPositionsMulti is stale as well; after the next-day gateway
   * restart production's reqPositionsMulti is corrected but reqPnLSingle is not (ARCHITECTURE
   * &sect;20.8), so that lag became 0 and a closing day failed again. The price basis stays
   * reqPnLSingle's own (its value), only the cost is replaced; on days without an add the feed
   * average equals the cost reqPnLSingle uses and the correction is 0. Production 2026-10-30: NVDA
   * 22,481.83 &minus; 110 &times; 193.46155755 = 1,201.06 against the raw 1,329.08, a difference of
   * 128.02, exactly that day's mismatch.
   */
  private double correctedUnrealized(String conId, IbkrAccounts.PnlSingleRow r) {
    IbkrAccounts.PositionRow p = positions.get(conId);
    if (p == null
        || p.position() == null
        || !p.position().isValid()
        || IbkrAccounts.amount(r.value()) == null) {
      return r.unrealized();
    }
    java.math.BigDecimal qty = p.position().value();
    // reqPnLSingle's own quantity must agree too, otherwise its value is for another lot size.
    if (r.position() != null
        && r.position().isValid()
        && r.position().value().compareTo(qty) != 0) {
      return r.unrealized();
    }
    Double feed = IbkrPortfolioFeed.averageCost(portfolioRows(), conId, qty);
    if (feed == null) {
      return r.unrealized();
    }
    double corrected = r.value() - feed * qty.doubleValue();
    return Double.isFinite(corrected) ? corrected : r.unrealized();
  }

  /**
   * Logs one line when the daily comparison is skipped on a closing day (at most one per
   * subscription).
   *
   * <p>Both daily figures and realized are printed to collect measured samples of "how far the
   * account daily and the per-position sum differ on closing days": on 2026-10-09 the difference
   * was &minus;5.84; if some day it reaches hundreds or thousands, the definition has some other
   * component, and there will be data to go on. It also shows whether realized really is 0 on
   * ordinary days (if it were "unset" all the time, this check would be skipped every day).
   */
  private void logDailyNotChecked(IbkrAccounts.PnlRow row, Double realized, double singlesDaily) {
    if (pnlDailySkipLogged) {
      return;
    }
    pnlDailySkipLogged = true;
    log.info(
        "当天有平仓（已实现={}），当日盈亏不与逐只之和比对、只用浮盈把关：账户当日={} 逐只之和当日={} 差={}",
        realized == null ? "未设" : realized,
        row.daily(),
        singlesDaily,
        row.daily() - singlesDaily);
  }

  /**
   * Records the numbers when verification fails, at most one line per subscription.
   *
   * <p>On 2026-09-23 in production the daily P&amp;L stayed blank all day and the log only said "no
   * valid push, re-subscribing (attempt N)", <b>not a single number</b>; afterwards IBKR's
   * definition could only be guessed by working backwards from net liquidation. This log exists so
   * the next failure leaves evidence instead of silence. No account number is logged.
   */
  private void logPnlMismatch(
      IbkrAccounts.PnlRow row,
      Double realized,
      double singlesDaily,
      double singlesUnrealized,
      double correction,
      boolean closedToday) {
    if (pnlMismatchLogged) {
      return;
    }
    pnlMismatchLogged = true;
    log.warn(
        "实时账户盈亏核对不通过（{} 只持仓）：账户 当日={} 浮盈={} 已实现={}；逐只之和 当日={} 浮盈={}（已按推送均价修正 {}）；"
            + "当日差={}（{}）浮盈差={}（容差 {}）",
        positions.size(),
        row.daily(),
        row.unrealized(),
        realized == null ? "未设" : realized,
        singlesDaily,
        singlesUnrealized,
        correction,
        row.daily() - singlesDaily,
        closedToday ? "当天有平仓，未参与判定" : "参与判定",
        row.unrealized() - singlesUnrealized,
        PNL_TOLERANCE);
  }

  @Override
  public String toString() {
    return "IbkrLiveAccount[**** subscribed="
        + subscribed()
        + " positions="
        + positions.size()
        + "]";
  }
}
