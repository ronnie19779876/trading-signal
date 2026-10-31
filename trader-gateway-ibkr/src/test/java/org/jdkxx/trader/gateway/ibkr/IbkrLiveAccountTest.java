package org.jdkxx.trader.gateway.ibkr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ib.client.Contract;
import com.ib.client.Decimal;
import com.ib.client.EClientSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.jdkxx.trader.domain.AccountPnl;
import org.jdkxx.trader.domain.AccountSummary;
import org.jdkxx.trader.domain.Position;
import org.jdkxx.trader.domain.PositionPnl;
import org.jdkxx.trader.domain.PositionPrice;
import org.jdkxx.trader.gateway.LiveAccountListener;
import org.jdkxx.trader.gateway.ibkr.mapper.IbkrAccounts;
import org.junit.jupiter.api.Test;

/**
 * State machine of the live-account subscription. Requests / cancels sent to the gateway are
 * checked against an EClientSocket mock; no real gateway is involved. The behavior is grounded in
 * {@link IbkrLiveAccount}'s class comment (read-only probe, 2026-09-19).
 */
class IbkrLiveAccountTest {

  private static final String ACCT = "ACCT-A";

  /**
   * Fake connection: records every request / cancel, keeps handlers by id, and fires delayed tasks
   * manually.
   */
  static final class FakeWire implements IbkrLiveAccount.Wire {
    Object token = new Object();
    int next = 100;
    final EClientSocket client = mock(EClientSocket.class);
    final Map<Integer, IbkrSubscriptions.Handler> handlers = new HashMap<>();

    /** Delayed tasks: recorded by delay, fired manually by the test. */
    final List<Map.Entry<Duration, Runnable>> timers = new ArrayList<>();

    boolean connected = true;

    @Override
    public Object sessionToken() {
      return token;
    }

    @Override
    public int nextId() {
      return next++;
    }

    @Override
    public boolean subscribe(String what, Consumer<EClientSocket> request) {
      if (!connected) return false;
      request.accept(client);
      return true;
    }

    @Override
    public void unsubscribe(String what, Consumer<EClientSocket> cancel) {
      cancel.accept(client);
    }

    @Override
    public void open(int id, IbkrSubscriptions.Handler handler) {
      handlers.put(id, handler);
    }

    @Override
    public void close(int id) {
      handlers.remove(id);
    }

    @Override
    public Runnable later(Duration delay, Runnable task) {
      Map.Entry<Duration, Runnable> e = Map.entry(delay, task);
      timers.add(e);
      return () -> timers.remove(e);
    }

    /** Takes out and runs the task with the given delay. */
    void fire(Duration delay) {
      Map.Entry<Duration, Runnable> e =
          timers.stream().filter(t -> t.getKey().equals(delay)).findFirst().orElseThrow();
      timers.remove(e);
      e.getValue().run();
    }

    long pending(Duration delay) {
      return timers.stream().filter(t -> t.getKey().equals(delay)).count();
    }

    /**
     * An advanceable clock. now() used to return a constant with no writable field, so
     * IbkrAccountSummaryFeed's MAX_AGE=10 minutes staleness check was <b>impossible to trigger by
     * construction</b>: all 13 feed tests landed in the age=0 fresh branch (found in the
     * full-project review on 2026-09-25).
     */
    Instant clock = Instant.parse("2026-09-21T14:00:00Z");

    /** Moves the clock forward, to get past MAX_AGE. */
    void advance(Duration by) {
      clock = clock.plus(by);
    }

    @Override
    public Instant now() {
      return clock;
    }
  }

  /** Listener that records what is pushed. */
  static final class Recorder implements LiveAccountListener {
    final List<AccountSummary> summaries = new ArrayList<>();
    final List<AccountPnl> pnls = new ArrayList<>();
    final List<List<Position>> positions = new ArrayList<>();
    final List<PositionPnl> singles = new ArrayList<>();
    final List<PositionPrice> prices = new ArrayList<>();

    @Override
    public void onSummary(AccountSummary s) {
      summaries.add(s);
    }

    @Override
    public void onPnl(AccountPnl p) {
      pnls.add(p);
    }

    @Override
    public void onPositions(List<Position> p) {
      positions.add(p);
    }

    @Override
    public void onPositionPnl(PositionPnl p) {
      singles.add(p);
    }

    @Override
    public void onPositionPrice(PositionPrice p) {
      prices.add(p);
    }
  }

  private final FakeWire wire = new FakeWire();
  private final Recorder rec = new Recorder();
  private final IbkrLiveAccount live = new IbkrLiveAccount(ACCT, rec, wire);

  // subscribe() takes ids in order: positions 100, account P&L 101 (the account summary is a
  // standing subscription since 3.0.9, see IbkrAccountSummaryFeedTest)
  private static final int POS = 100, PNL = 101;

  private static IbkrAccounts.PositionRow pos(int conId, String symbol, double qty) {
    Contract c = new Contract();
    c.conid(conId);
    c.symbol(symbol);
    c.secType("STK");
    c.currency("USD");
    return new IbkrAccounts.PositionRow(ACCT, c, Decimal.get(qty), 100.0);
  }

  // two positions: after positions.end() it opens IBKR per-position 102, IBKR market data 103, SPY
  // per-position 104, SPY market data 105, in that order
  private static final int SINGLE_IBKR = 102, SINGLE_SPY = 104;

  /**
   * Positions complete (IBKR + SPY); per-position P&amp;L: daily sum 915.99, unrealized sum
   * 22,328.76 (the 09-19 production account's figures split across two positions).
   */
  private void positionsAndSingles(boolean withSingles) {
    live.subscribe();
    wire.handlers.get(POS).item(pos(43645865, "IBKR", 11.1142));
    wire.handlers.get(POS).item(pos(756733, "SPY", 210));
    wire.handlers.get(POS).end();
    if (withSingles) {
      wire.handlers
          .get(SINGLE_IBKR)
          .item(
              new IbkrAccounts.PnlSingleRow(
                  Decimal.get(11.1142), 25.67, 183.65, Double.MAX_VALUE, 1007.95));
      wire.handlers
          .get(SINGLE_SPY)
          .item(
              new IbkrAccounts.PnlSingleRow(
                  Decimal.get(210), 890.32, 22145.11, Double.MAX_VALUE, 160225.80));
    }
  }

  /**
   * Guard: right after connecting, the first account P&amp;L push covers only one position (25.67)
   * and does not match the per-position sum, so it is not adopted; the next one that matches is.
   */
  @Test
  void 与逐只之和对不上的账户盈亏不采用() {
    positionsAndSingles(true);
    wire.handlers.get(PNL).item(new IbkrAccounts.PnlRow(25.67, 183.65, 0));
    assertThat(rec.pnls).isEmpty();

    wire.handlers.get(PNL).item(new IbkrAccounts.PnlRow(915.99, 22328.76, 0));
    assertThat(rec.pnls).extracting(x -> x.daily().toPlainString()).containsExactly("915.99");
  }

  /**
   * Guard: when something was closed today (realized &ne; 0) daily is <b>not compared</b>, only
   * unrealized guards; however far daily is off, it does not affect adoption.
   *
   * <p>Here daily is off by 364.51 and unrealized matches exactly; under the new rule it must be
   * adopted. 3.0.10's "subtract realized, then compare" happens to pass these numbers too (1280.50
   * &minus; 364.51 = 915.99), so it <b>cannot tell old from new</b>; the test that really tells
   * them apart is {@link #按2026_10_09生产真实数字_平仓日照常采用()}.
   */
  @Test
  void 有平仓时不比当日_只看浮盈() {
    positionsAndSingles(true);

    wire.handlers.get(PNL).item(new IbkrAccounts.PnlRow(1280.50, 22328.76, 364.51));

    assertThat(rec.pnls).extracting(x -> x.daily().toPlainString()).containsExactly("1280.5");
    assertThat(rec.pnls.get(0).realized().toPlainString())
        .isEqualTo("364.51"); // what is shown is still IBKR's raw value
  }

  /**
   * <b>This is the crux of this fix</b>; every number comes from the 2026-10-09 production log (the
   * day 80 BRK.B shares were sold):
   *
   * <pre>
   * account          daily=1093.3018226278286  unrealized=26615.850201798563  realized=477.157432
   * per-position sum daily=1099.14422262783    unrealized=26615.850201798563
   * </pre>
   *
   * Unrealized difference 0.0, daily difference &minus;5.8424. With realized subtracted (3.0.10)
   * the difference became &minus;483.00 and the daily P&amp;L stayed blank all day. New rule: daily
   * is not compared on closing days, so it is adopted.
   *
   * <p>The per-position rows are split into two here; the logic only looks at the totals, so this
   * is equivalent to the real 5 positions.
   */
  @Test
  void 按2026_10_09生产真实数字_平仓日照常采用() {
    live.subscribe();
    wire.handlers.get(POS).item(pos(43645865, "IBKR", 11.1142));
    wire.handlers.get(POS).item(pos(756733, "SPY", 210));
    wire.handlers.get(POS).end();
    // totals: daily 1099.14422262783, unrealized 26615.850201798563
    wire.handlers
        .get(SINGLE_IBKR)
        .item(
            new IbkrAccounts.PnlSingleRow(
                Decimal.get(11.1142), 99.14422262783, 615.850201798563, Double.MAX_VALUE, 1007.95));
    wire.handlers
        .get(SINGLE_SPY)
        .item(
            new IbkrAccounts.PnlSingleRow(
                Decimal.get(210), 1000.0, 26000.0, Double.MAX_VALUE, 160225.80));

    wire.handlers
        .get(PNL)
        .item(new IbkrAccounts.PnlRow(1093.3018226278286, 26615.850201798563, 477.157432));

    assertThat(rec.pnls).as("平仓日的当日盈亏不该再整天空着").hasSize(1);
    assertThat(rec.pnls.get(0).daily().doubleValue()).isCloseTo(1093.3018226278286, within(1e-9));
    assertThat(rec.pnls.get(0).realized().doubleValue()).isCloseTo(477.157432, within(1e-9));
  }

  /**
   * Guard: on days without a close realized = 0 and the behavior is exactly as before 3.0.9 (zero
   * regression).
   */
  @Test
  void 无平仓时口径不变() {
    positionsAndSingles(true);

    wire.handlers
        .get(PNL)
        .item(
            new IbkrAccounts.PnlRow(
                25.67, 183.65, 0)); // first push covers only one position: still not adopted
    assertThat(rec.pnls).isEmpty();

    wire.handlers.get(PNL).item(new IbkrAccounts.PnlRow(915.99, 22328.76, 0));
    assertThat(rec.pnls).hasSize(1);
  }

  /**
   * Guard: when realized is unset (Double.MAX_VALUE, mapped to null) <b>it cannot be decided
   * whether anything was closed today</b>, so it is treated as "closed": compare unrealized only.
   * Better one check fewer than a daily P&amp;L left blank all day on a closing day (both
   * 2026-09-23 and 2026-10-09 fell into this). The log marks realized as unset so the case can be
   * told apart afterwards.
   */
  @Test
  void 已实现未设时只用浮盈把关() {
    positionsAndSingles(true);

    // daily is 364.51 above the per-position sum, but unrealized matches exactly: adopted
    wire.handlers.get(PNL).item(new IbkrAccounts.PnlRow(1280.50, 22328.76, Double.MAX_VALUE));
    assertThat(rec.pnls).hasSize(1);
  }

  /**
   * Guard: with realized unset, unrealized is still a hard gate: an incomplete push is still
   * blocked.
   */
  @Test
  void 已实现未设但浮盈对不上_仍不采用() {
    positionsAndSingles(true);

    wire.handlers.get(PNL).item(new IbkrAccounts.PnlRow(25.67, 183.65, Double.MAX_VALUE));
    assertThat(rec.pnls).isEmpty();
  }

  /**
   * Guard: skipping the daily comparison on a closing day <b>does not mean</b> unrealized is
   * relaxed too: both sides define unrealized the same way, so it is a hard gate at all times.
   */
  @Test
  void 浮盈对不上仍然不采用() {
    positionsAndSingles(true);

    wire.handlers.get(PNL).item(new IbkrAccounts.PnlRow(1280.50, 99999.99, 364.51));

    assertThat(rec.pnls).isEmpty();
  }

  /**
   * Guard: re-subscribing after per-position P&amp;L has been running steadily, the account P&amp;L
   * pushes a single, correct push and no second one while prices stand still (measured 09-19): that
   * push must be adopted. 3.0.3 always dropped the first push, so the daily P&amp;L kept showing
   * "waiting for IBKR".
   */
  @Test
  void 唯一一条正确推送直接采用() {
    positionsAndSingles(true);
    wire.handlers.get(PNL).item(new IbkrAccounts.PnlRow(915.99, 22328.76, 0));
    assertThat(rec.pnls).hasSize(1);
  }

  /**
   * Account P&amp;L first, per-position later: held until per-position rows are complete and it
   * verifies, then emitted; once adopted, later pushes pass straight through.
   */
  @Test
  void 账户盈亏先到时等逐只到齐再核() {
    positionsAndSingles(false);
    wire.handlers.get(PNL).item(new IbkrAccounts.PnlRow(915.99, 22328.76, 0));
    assertThat(rec.pnls).isEmpty();

    wire.handlers
        .get(SINGLE_IBKR)
        .item(
            new IbkrAccounts.PnlSingleRow(
                Decimal.get(11.1142), 25.67, 183.65, Double.MAX_VALUE, 1007.95));
    assertThat(rec.pnls).isEmpty(); // SPY still missing
    wire.handlers
        .get(SINGLE_SPY)
        .item(
            new IbkrAccounts.PnlSingleRow(
                Decimal.get(210), 890.32, 22145.11, Double.MAX_VALUE, 160225.80));
    assertThat(rec.pnls).hasSize(1);

    wire.handlers
        .get(PNL)
        .item(
            new IbkrAccounts.PnlRow(
                920.00, 22332.77,
                0)); // changed intraday: already verified, passes straight through
    assertThat(rec.pnls).hasSize(2);
  }

  /**
   * Guard: per-position P&amp;L follows the positions: after End one subscription per position; a
   * closed position (quantity 0) is unsubscribed and removed from the list.
   */
  @Test
  void 逐只盈亏跟着持仓订退() {
    live.subscribe();
    IbkrSubscriptions.Handler positions = wire.handlers.get(POS);
    positions.item(pos(265598, "AAPL", 10));
    positions.item(pos(72063691, "BRK B", 80));
    verify(wire.client, never())
        .reqPnLSingle(anyInt(), eq(ACCT), eq(""), anyInt()); // nothing subscribed before End

    positions.end();
    verify(wire.client).reqPnLSingle(anyInt(), eq(ACCT), eq(""), eq(265598));
    verify(wire.client).reqPnLSingle(anyInt(), eq(ACCT), eq(""), eq(72063691));
    assertThat(rec.positions.get(0)).extracting(Position::symbol).containsExactly("AAPL", "BRK B");
    assertThat(live.singleCount()).isEqualTo(2);

    positions.item(pos(265598, "AAPL", 0)); // closed intraday
    verify(wire.client).cancelPnLSingle(anyInt());
    assertThat(rec.positions.get(1)).extracting(Position::symbol).containsExactly("BRK B");
    assertThat(live.singleCount()).isEqualTo(1);
  }

  /**
   * Subscribing while disconnected fails: no half-built state is left, and the whole set can be
   * subscribed once connected.
   */
  @Test
  void 未连接时不留半截订阅() {
    wire.connected = false;
    assertThat(live.subscribe()).isFalse();
    assertThat(live.subscribed()).isFalse();
    assertThat(wire.handlers).isEmpty();

    wire.connected = true;
    assertThat(live.subscribe()).isTrue();
    assertThat(wire.handlers)
        .hasSize(
            2); // positions + account P&L; the account summary moved to the standing subscription
  }

  @Test
  void 逐只盈亏的未设值映射成空且账户号不进字符串() {
    PositionPnl p =
        IbkrAccounts.positionPnl(
            "756733",
            new IbkrAccounts.PnlSingleRow(
                Decimal.get(210), 449.15, 19828.81, Double.MAX_VALUE, 160198.49),
            Instant.EPOCH);
    assertThat(p.realized()).isNull();
    assertThat(p.marketValue()).isEqualByComparingTo("160198.49");
    live.subscribe();
    assertThat(live.toString()).doesNotContain(ACCT);
  }

  private static final Duration WATCHDOG = Duration.ofSeconds(IbkrLiveAccount.PNL_WATCHDOG_SECONDS);

  /**
   * Market data follows the positions: after End, subscribe by conId via SMART; closed positions
   * are unsubscribed.
   */
  @Test
  void 持仓行情跟着持仓订退() {
    live.subscribe();
    IbkrSubscriptions.Handler positions = wire.handlers.get(POS);
    positions.item(pos(208813720, "GOOG", 62));
    positions.end();

    org.mockito.ArgumentCaptor<Contract> c = org.mockito.ArgumentCaptor.forClass(Contract.class);
    verify(wire.client)
        .reqMktData(
            anyInt(),
            c.capture(),
            eq(""),
            eq(false),
            eq(false),
            org.mockito.ArgumentMatchers.isNull());
    assertThat(c.getValue().conid()).isEqualTo(208813720);
    assertThat(c.getValue().exchange()).isEqualTo("SMART");
    assertThat(live.quoteCount()).isEqualTo(1);

    positions.item(pos(208813720, "GOOG", 0));
    verify(wire.client).cancelMktData(anyInt());
    assertThat(live.quoteCount()).isZero();
  }

  /**
   * Only the last price (LAST) and previous close (CLOSE) are taken; the -1 bid/ask after the close
   * and other ticks are ignored; both are emitted together; delayed data must be flagged.
   */
  @Test
  void 现价只取最新价与前收() {
    live.subscribe();
    wire.handlers.get(POS).item(pos(208813720, "GOOG", 62));
    wire.handlers.get(POS).end();
    IbkrSubscriptions.Handler quote =
        wire.handlers.entrySet().stream()
            .filter(e -> e.getKey() > PNL)
            .reduce((a, b) -> b)
            .orElseThrow()
            .getValue(); // the last one opened is the market data

    quote.item(new IbkrAccounts.TickRow(1, -1.0)); // bid -1 after the close: ignored
    quote.item(new IbkrAccounts.TickRow(14, 351.35)); // open price: not needed
    quote.item(new IbkrAccounts.TickRow(9, 343.68)); // previous close
    quote.item(new IbkrAccounts.TickRow(4, 346.08)); // last price
    assertThat(rec.prices).hasSize(2);
    assertThat(rec.prices.get(0).last()).isNull();
    assertThat(rec.prices.get(0).priorClose()).isEqualByComparingTo("343.68");
    assertThat(rec.prices.get(1).last()).isEqualByComparingTo("346.08");
    assertThat(rec.prices.get(1).priorClose()).isEqualByComparingTo("343.68");
    assertThat(rec.prices.get(1).delayed()).isFalse();

    quote.item(new IbkrAccounts.MarketDataTypeRow(3)); // downgraded to delayed data
    quote.item(new IbkrAccounts.TickRow(68, 346.10));
    assertThat(rec.prices.get(2).delayed()).isTrue();
  }

  /**
   * Guard: no push ever verifies (only the wrong one came, no second): re-subscribe after 10
   * seconds; no more re-subscribing once one verifies.
   */
  @Test
  void 账户盈亏没有有效值时重订() {
    positionsAndSingles(true);
    wire.handlers.get(PNL).item(new IbkrAccounts.PnlRow(25.67, 183.65, 0)); // wrong, not adopted
    assertThat(rec.pnls).isEmpty();

    wire.fire(WATCHDOG);
    verify(wire.client).cancelPnL(PNL);
    int second = wire.handlers.keySet().stream().max(Integer::compare).orElseThrow();
    wire.handlers
        .get(second)
        .item(
            new IbkrAccounts.PnlRow(
                991.93, 23308.54, 0)); // the first push after re-subscribing may be wrong too
    wire.handlers.get(second).item(new IbkrAccounts.PnlRow(915.99, 22328.76, 0));
    assertThat(rec.pnls).extracting(x -> x.daily().toPlainString()).containsExactly("915.99");

    wire.fire(WATCHDOG); // a valid value exists: no further re-subscribe
    verify(wire.client, org.mockito.Mockito.times(1)).cancelPnL(anyInt());
  }

  /** At most 3 re-subscribes, after that the gateway is left alone. */
  @Test
  void 账户盈亏重订有上限() {
    live.subscribe();
    for (int i = 0; i < IbkrLiveAccount.PNL_MAX_RETRIES; i++) {
      wire.fire(WATCHDOG);
    }
    assertThat(wire.pending(WATCHDOG)).isZero();
    verify(wire.client, org.mockito.Mockito.times(IbkrLiveAccount.PNL_MAX_RETRIES))
        .cancelPnL(anyInt());
  }

  // ---- account summary hitting the 2-per-client limit (322): measured in production 2026-09-21,
  // after the gateway's daily restart and reconnect no funds data all morning ----

  // ---- 3.4.6: verification on add days, and the average cost / unrealized supplied by the
  // account-updates feed (measured in production 2026-10-20 to 10-22) ----

  /**
   * <b>This is the crux of 3.4.6</b>; numbers from the 2026-10-21 production log (after adding 100
   * GOOG shares on 10-20):
   *
   * <pre>
   * account          daily=1243.913539249866  unrealized=23792.73481422064  realized=0.0
   * per-position sum daily=1243.913539249866  unrealized=22613.089629420636
   * </pre>
   *
   * Daily difference 0.0, unrealized difference 1,179.645 (reqPnLSingle still uses the pre-add
   * average cost). The old rule blanked the account P&amp;L all day because unrealized did not
   * match; new rule: on days without a close only daily is compared, so it is adopted.
   */
  @Test
  void 加仓日浮盈对不上但当日一致_采用() {
    live.subscribe();
    wire.handlers.get(POS).item(pos(43645865, "IBKR", 11.1142));
    wire.handlers.get(POS).item(pos(756733, "SPY", 210));
    wire.handlers.get(POS).end();
    // totals: daily 1243.913539249866, unrealized 22613.089629420636
    wire.handlers
        .get(SINGLE_IBKR)
        .item(
            new IbkrAccounts.PnlSingleRow(
                Decimal.get(11.1142),
                243.913539249866,
                613.089629420636,
                Double.MAX_VALUE,
                920.26));
    wire.handlers
        .get(SINGLE_SPY)
        .item(
            new IbkrAccounts.PnlSingleRow(
                Decimal.get(210), 1000.0, 22000.0, Double.MAX_VALUE, 161809.20));

    wire.handlers.get(PNL).item(new IbkrAccounts.PnlRow(1243.913539249866, 23792.73481422064, 0));

    assertThat(rec.pnls).as("加仓日的账户盈亏不该再整天空着").hasSize(1);
    assertThat(rec.pnls.get(0).unrealized().doubleValue())
        .as("显示仍是账户口径原值")
        .isCloseTo(23792.73481422064, within(1e-9));
  }

  /**
   * Guard: comparing only daily is not a relaxation: an incomplete first push still fails on daily
   * (production 2026-10-22: off by 48.74) and is blocked.
   */
  @Test
  void 没有平仓时当日对不上仍不采用() {
    positionsAndSingles(true);

    wire.handlers.get(PNL).item(new IbkrAccounts.PnlRow(915.99 + 48.74, 22328.76, 0));

    assertThat(rec.pnls).isEmpty();
  }

  private static IbkrAccounts.PositionRow pos(int conId, String symbol, double qty, double avg) {
    IbkrAccounts.PositionRow p = pos(conId, symbol, qty);
    return new IbkrAccounts.PositionRow(p.account(), p.contract(), p.position(), avg);
  }

  /**
   * Production 2026-10-30: on the same day SGOV was closed out (a close, so only unrealized is
   * compared) and 10 NVDA shares were added (reqPnLSingle unrealized at the pre-add average cost).
   * Raw log numbers:
   *
   * <pre>
   * account          daily=-2173.1959660586967  unrealized=21201.05744131204  realized=137.0360184
   * per-position sum daily=-2215.599096058722   unrealized=21329.08037181204   unrealizedDiff=-128.02293049999935
   * </pre>
   *
   * NVDA positions-API average cost 192.2977127272727, account-updates feed 193.46155755: 110
   * &times; 1.16384482 = 128.0229305, exactly the unrealized difference. Since 3.5.1 the
   * per-position unrealized is corrected with the feed's average cost and matches, so it is
   * adopted; the account-level raw value is still what is shown. The per-position rows are split
   * into NVDA + SPY here; only totals matter.
   *
   * <p>Each per-position value obeys reqPnLSingle's own identity value = unrealized + quantity
   * &times; the (stale) cost it uses (09-19 probe, GOOG: 21,353.42 &minus; (&minus;148.58) =
   * 21,502.00 = 62 &times; 346.806455). Fixtures rebuilt in the 2026-10-31 review: the earlier
   * synthetic values broke that identity, and since 3.5.2 the check derives unrealized from value.
   */
  private void sameDayCloseAndAdd() {
    sameDayCloseAndAdd(NVDA_STALE_AVG, NVDA_VALUE);
  }

  private static final double NVDA_STALE_AVG = 192.2977127272727;
  private static final double NVDA_FEED_AVG = 193.46155755;
  // 1329.08037181204 + 110 * 192.2977127272727
  private static final double NVDA_VALUE = 22481.828771812037;
  private static final double SPY_AVG = 668.4270476;
  // 20000.0 + 210 * 668.4270476
  private static final double SPY_VALUE = 160369.67999600002;

  /**
   * @param nvdaPositionsAvg NVDA average cost from the positions API: stale on the day of the add,
   *     corrected after the next-day gateway restart
   */
  private void sameDayCloseAndAdd(double nvdaPositionsAvg, double nvdaValue) {
    live.subscribe();
    wire.handlers.get(POS).item(pos(4815747, "NVDA", 110, nvdaPositionsAvg));
    wire.handlers.get(POS).item(pos(756733, "SPY", 210, SPY_AVG));
    wire.handlers.get(POS).end();
    // the first position takes 102, the second 104 (same numbering as positionsAndSingles); total
    // unrealized 21329.08037181204 (NVDA on the stale cost)
    wire.handlers
        .get(SINGLE_IBKR)
        .item(
            new IbkrAccounts.PnlSingleRow(
                Decimal.get(110), -840.6, 1329.08037181204, Double.MAX_VALUE, nvdaValue));
    wire.handlers
        .get(SINGLE_SPY)
        .item(
            new IbkrAccounts.PnlSingleRow(
                Decimal.get(210), -1374.999096058722, 20000.0, Double.MAX_VALUE, SPY_VALUE));
  }

  @Test
  void 平仓与加仓同一天_按新均价修正逐只浮盈后采用() {
    sameDayCloseAndAdd();
    portfolio(
        feedRow(4815747, 110, 193.46155755, 1293.43), feedRow(756733, 210, 668.4270476, 18547.82));

    wire.handlers
        .get(PNL)
        .item(new IbkrAccounts.PnlRow(-2173.1959660586967, 21201.05744131204, 137.0360184));

    assertThat(rec.pnls).as("10-30 那天账户盈亏不该整天空着").hasSize(1);
    assertThat(rec.pnls.get(0).unrealized().doubleValue())
        .as("显示仍是账户口径原值")
        .isCloseTo(21201.05744131204, within(1e-9));
  }

  /**
   * Guard: without the account-updates feed (dev instance) there is no correction and the behavior
   * is the same as 3.5.0: still fails.
   */
  @Test
  void 没有账户更新推送时不修正() {
    sameDayCloseAndAdd();

    wire.handlers
        .get(PNL)
        .item(new IbkrAccounts.PnlRow(-2173.1959660586967, 21201.05744131204, 137.0360184));

    assertThat(rec.pnls).isEmpty();
  }

  /**
   * Guard: when the feed's quantity differs from the positions API (the add's push has not arrived
   * yet) it is not used for the correction, to avoid applying the wrong batch's average cost.
   */
  @Test
  void 推送数量不一致时不修正() {
    sameDayCloseAndAdd();
    portfolio(
        feedRow(4815747, 100, 193.46155755, 1293.43), feedRow(756733, 210, 668.4270476, 18547.82));

    wire.handlers
        .get(PNL)
        .item(new IbkrAccounts.PnlRow(-2173.1959660586967, 21201.05744131204, 137.0360184));

    assertThat(rec.pnls).isEmpty();
  }

  /**
   * Guard: correcting is not relaxing: if unrealized is still far off after the correction (50 more
   * here) it is still not adopted.
   */
  @Test
  void 修正后仍对不上照样不采用() {
    sameDayCloseAndAdd();
    portfolio(
        feedRow(4815747, 110, 193.46155755, 1293.43), feedRow(756733, 210, 668.4270476, 18547.82));

    wire.handlers
        .get(PNL)
        .item(new IbkrAccounts.PnlRow(-2173.1959660586967, 21201.05744131204 - 50, 137.0360184));

    assertThat(rec.pnls).isEmpty();
  }

  /**
   * Regression found in the 2026-10-31 review: after the next-day gateway restart, production's
   * reqPositionsMulti reports the corrected cost while reqPnLSingle's unrealized still uses the
   * pre-add cost (ARCHITECTURE &sect;20.8 table). 3.5.1's lag = (feed average &minus; positions
   * average) &times; quantity is then 0 and a closing day's unrealized check is off by 128.02
   * again. The correction now comes from reqPnLSingle's own value: value &minus; feed average
   * &times; quantity.
   */
  @Test
  void 持仓接口已改对但逐只浮盈仍滞后_平仓日照样采用() {
    sameDayCloseAndAdd(NVDA_FEED_AVG, NVDA_VALUE);
    portfolio(
        feedRow(4815747, 110, NVDA_FEED_AVG, 1293.43), feedRow(756733, 210, SPY_AVG, 18547.82));

    wire.handlers
        .get(PNL)
        .item(new IbkrAccounts.PnlRow(-2173.1959660586967, 21201.05744131204, 137.0360184));

    assertThat(rec.pnls).as("持仓接口改对之后的平仓日，账户盈亏也不该整天空着").hasSize(1);
  }

  /**
   * Guard: an unset value (Double.MAX_VALUE) must not yield a "corrected" unrealized; that position
   * falls back to the raw unrealized and the check fails closed.
   */
  @Test
  void 逐只市值未设时不修正() {
    sameDayCloseAndAdd(NVDA_STALE_AVG, Double.MAX_VALUE);
    portfolio(
        feedRow(4815747, 110, NVDA_FEED_AVG, 1293.43), feedRow(756733, 210, SPY_AVG, 18547.82));

    wire.handlers
        .get(PNL)
        .item(new IbkrAccounts.PnlRow(-2173.1959660586967, 21201.05744131204, 137.0360184));

    assertThat(rec.pnls).isEmpty();
  }

  /**
   * Guard: an unset value means "no correction", not a huge one. No add here (positions, feed and
   * reqPnLSingle all on the same cost), so the raw unrealized is right and must still be adopted.
   */
  @Test
  void 逐只市值未设且没有加仓时用原浮盈采用() {
    sameDayCloseAndAdd(NVDA_STALE_AVG, Double.MAX_VALUE);
    portfolio(
        feedRow(4815747, 110, NVDA_STALE_AVG, 1293.43), feedRow(756733, 210, SPY_AVG, 18547.82));

    wire.handlers
        .get(PNL)
        .item(new IbkrAccounts.PnlRow(-2173.1959660586967, 21329.08037181204, 137.0360184));

    assertThat(rec.pnls).hasSize(1);
  }

  /**
   * With the account-updates feed attached: once the first batch completes positions are
   * re-emitted, the one with a matching quantity gets the feed's average cost, the rest are
   * unchanged.
   */
  private IbkrPortfolioFeed portfolio(IbkrAccounts.PortfolioRow... rows) {
    IbkrPortfolioFeed feed = new IbkrPortfolioFeed(wire, () -> ACCT);
    feed.onChange(live::onPortfolioChanged);
    live.portfolio(feed);
    feed.subscribe(false);
    for (IbkrAccounts.PortfolioRow r : rows) {
      wire.handlers.get(IbkrSubscriptions.PORTFOLIO).item(r);
    }
    wire.handlers.get(IbkrSubscriptions.PORTFOLIO).end();
    return feed;
  }

  private static IbkrAccounts.PortfolioRow feedRow(
      int conId, double qty, double avg, double unrealized) {
    Contract c = new Contract();
    c.conid(conId);
    return new IbkrAccounts.PortfolioRow(ACCT, c, Decimal.get(qty), avg, unrealized, 82.8, 920.26);
  }

  @Test
  void 持仓均价取账户更新推送_数量不一致时用原值() {
    positionsAndSingles(true);
    assertThat(rec.positions.getLast())
        .extracting(p -> p.averageCost().doubleValue())
        .containsExactly(100.0, 100.0);

    portfolio(
        feedRow(43645865, 11.1142, 74.16640875, 95.96),
        feedRow(756733, 200, 668.4270476, 21439.52));

    List<Position> last = rec.positions.getLast();
    assertThat(last.get(0).averageCost())
        .as("IBKR 数量一致：换成推送的均价")
        .isEqualByComparingTo("74.16640875");
    assertThat(last.get(1).averageCost())
        .as("SPY 推送是 200 股、持仓接口是 210 股：不换")
        .isEqualByComparingTo("100.0");
  }

  @Test
  void 逐只浮盈取账户更新推送_当日与市值仍是逐只盈亏原值() {
    positionsAndSingles(false);
    portfolio(feedRow(43645865, 11.1142, 74.16640875, 95.96));

    wire.handlers
        .get(SINGLE_IBKR)
        .item(
            new IbkrAccounts.PnlSingleRow(
                Decimal.get(11.1142), 25.67, 183.65, Double.MAX_VALUE, 1007.95));

    PositionPnl p = rec.singles.getLast();
    assertThat(p.unrealized()).isEqualByComparingTo("95.96");
    assertThat(p.daily()).isEqualByComparingTo("25.67");
    assertThat(p.marketValue()).isEqualByComparingTo("1007.95");
  }

  /**
   * Feed arriving after the per-position P&amp;L: when it takes effect the existing per-position
   * P&amp;L is re-emitted with the new unrealized, so the page need not wait for the next
   * per-position push.
   */
  @Test
  void 推送生效时重发逐只盈亏() {
    positionsAndSingles(true);
    int before = rec.singles.size();

    portfolio(feedRow(43645865, 11.1142, 74.16640875, 95.96));

    assertThat(rec.singles.size()).isEqualTo(before + 2);
    assertThat(
            rec.singles.stream()
                .filter(x -> x.brokerRef().equals("43645865"))
                .reduce((a, b) -> b)
                .orElseThrow()
                .unrealized())
        .isEqualByComparingTo("95.96");
  }

  /**
   * Verification still uses the raw per-position P&amp;L: the feed's <b>unrealized</b> (a batch
   * every 3 minutes, at IBKR's own mark) is not involved, so differing sample times cannot make it
   * fail. Since 3.5.1 the feed's <b>average cost</b> is involved (correcting the cost lag on add
   * days; since 3.5.2 as value &minus; feed average &times; quantity), so here each value is built
   * on the feed's average; on days without an add they agree anyway and the correction is 0.
   */
  @Test
  void 核对不受账户更新推送影响() {
    live.subscribe();
    wire.handlers.get(POS).item(pos(43645865, "IBKR", 11.1142, 74.16640875));
    wire.handlers.get(POS).item(pos(756733, "SPY", 210, 668.4270476));
    wire.handlers.get(POS).end();
    wire.handlers
        .get(SINGLE_IBKR)
        .item(
            new IbkrAccounts.PnlSingleRow(
                Decimal.get(11.1142), 25.67, 183.65, Double.MAX_VALUE, 1007.95));
    wire.handlers
        .get(SINGLE_SPY)
        .item(
            new IbkrAccounts.PnlSingleRow(
                Decimal.get(210), 890.32, 22145.11, Double.MAX_VALUE, 162514.789996));
    // SPY value = 22145.11 + 210 * 668.4270476 (the old 160225.80 broke the identity)
    portfolio(
        feedRow(43645865, 11.1142, 74.16640875, 9999.0), feedRow(756733, 210, 668.4270476, 9999.0));

    wire.handlers
        .get(PNL)
        .item(
            new IbkrAccounts.PnlRow(
                1280.50, 22328.76,
                364.51)); // closing day: unrealized compared (raw per-position sum 22328.76)

    assertThat(rec.pnls).hasSize(1);
  }
}
