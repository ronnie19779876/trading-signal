package org.jdkxx.trader.gateway.ibkr.mapper;

import com.ib.client.Contract;
import com.ib.client.Decimal;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jdkxx.trader.domain.AccountPnl;
import org.jdkxx.trader.domain.AccountSummary;
import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.domain.Position;
import org.jdkxx.trader.domain.PositionPnl;
import org.jdkxx.trader.gateway.GatewayException;

/**
 * TWS position / account-summary / P&amp;L callbacks to domain objects. The record types here are
 * the raw callback payloads: the pump thread only packs them, mapping happens later on the dispatch
 * thread.
 *
 * <p>Measured (TWS API 10.30.01, read-only probe against a real gateway on 2026-09-14):
 *
 * <ul>
 *   <li>{@code positionMulti}: primaryExch is null while exchange is set; class-share symbols
 *       contain a space ({@code BRK B}); quantities can be fractional;
 *   <li>the value of {@code accountSummary}'s {@code $LEDGER-AccountOrGroup} is the plaintext
 *       account number and must be dropped;
 *   <li>{@code SettledCash} is not returned for this account type, so it is not requested;
 *   <li>cash + stock market value + accrued dividends + accrued interest ({@code
 *       $LEDGER-AccruedCash}) equal net liquidation exactly. The original probe matched with the
 *       first three terms only because accrued interest happened to be 0; the fourth term was added
 *       in 3.1.3.
 * </ul>
 */
public final class IbkrAccounts {

  /**
   * Requested tags: the usual funds items plus the USD ledger (stock market value,
   * unrealized/realized P&amp;L and accrued dividends exist only under $LEDGER).
   */
  public static final String SUMMARY_TAGS =
      "AccountType,NetLiquidation,TotalCashValue,GrossPositionValue,AvailableFunds,BuyingPower,ExcessLiquidity,$LEDGER:USD";

  static final String ACCOUNT_OR_GROUP = "$LEDGER-AccountOrGroup";

  /**
   * Raw positionMulti payload: only packed on the pump thread, mapped on the dispatch thread.
   * {@code toString} masks the account so the row can be logged safely.
   */
  public record PositionRow(
      String account, Contract contract, Decimal position, double averageCost) {

    @Override
    public String toString() {
      return "PositionRow[**** "
          + (contract == null ? null : contract.symbol())
          + " "
          + position
          + "]";
    }
  }

  /**
   * Raw accountSummary payload. {@code toString} omits the account and the value (which may be the
   * account number).
   */
  public record SummaryRow(String account, String tag, String value, String currency) {

    @Override
    public String toString() {
      return "SummaryRow[**** " + tag + "]";
    }
  }

  /**
   * One position row from the account-updates feed (reqAccountUpdates, updatePortfolio; 3.4.6).
   * Same source as the IBKR app: after adding to GOOG on 2026-10-20, the average cost from
   * reqPositionsMulti / reqPositions and the unrealized P&amp;L from reqPnLSingle were still based
   * on the pre-add cost; only this feed (and account-level reqPnL) was right, see ARCHITECTURE
   * §20.8.
   */
  public record PortfolioRow(
      String account,
      Contract contract,
      Decimal position,
      double averageCost,
      double unrealized,
      double marketPrice,
      double marketValue) {}

  /** Raw pnl payload (account level, used by the live-account subscription). */
  public record PnlRow(double daily, double unrealized, double realized) {}

  /** Raw pnlSingle payload (one position). */
  public record PnlSingleRow(
      Decimal position, double daily, double unrealized, double realized, double value) {}

  /** Raw tickPrice payload. */
  public record TickRow(int field, double price) {}

  /** marketDataType callback: 1 real-time, 2 frozen, 3 delayed, 4 delayed frozen. */
  public record MarketDataTypeRow(int type) {}

  /**
   * Tick types for the last price: LAST = 4, DELAYED_LAST = 68 under delayed data; previous close:
   * CLOSE = 9, DELAYED_CLOSE = 75 under delayed data.
   */
  public static final int TICK_LAST = 4;

  public static final int TICK_DELAYED_LAST = 68;
  public static final int TICK_CLOSE = 9;
  public static final int TICK_DELAYED_CLOSE = 75;

  private IbkrAccounts() {}

  /** Whether this tick is a last price / a previous close. */
  public static boolean isLast(int field) {
    return field == TICK_LAST || field == TICK_DELAYED_LAST;
  }

  public static boolean isClose(int field) {
    return field == TICK_CLOSE || field == TICK_DELAYED_CLOSE;
  }

  /**
   * A usable price; invalid values such as the -1 bid/ask after the close (measured) return null.
   */
  public static BigDecimal price(double v) {
    BigDecimal p = amount(v);
    return p == null || p.signum() <= 0 ? null : p;
  }

  public static AccountPnl pnl(PnlRow r, Instant receivedAt) {
    return new AccountPnl(
        Broker.IBKR, receivedAt, amount(r.daily()), amount(r.unrealized()), amount(r.realized()));
  }

  /** Measured (2026-09-19): realized is always Double.MAX_VALUE (unset), mapped to null. */
  public static PositionPnl positionPnl(String conId, PnlSingleRow r, Instant receivedAt) {
    BigDecimal qty = r.position() == null || !r.position().isValid() ? null : r.position().value();
    return new PositionPnl(
        Broker.IBKR,
        conId,
        receivedAt,
        qty,
        amount(r.daily()),
        amount(r.unrealized()),
        amount(r.realized()),
        amount(r.value()));
  }

  /**
   * TWS uses Double.MAX_VALUE for "unset"; NaN and infinities are treated as absent as well. Goes
   * through {@code BigDecimal.valueOf} (the shortest decimal string of the double), so values like
   * 1093.3018 do not pick up binary noise.
   */
  public static BigDecimal amount(double v) {
    return Double.isNaN(v) || Double.isInfinite(v) || v == Double.MAX_VALUE
        ? null
        : BigDecimal.valueOf(v);
  }

  public static List<Position> positions(String accountId, List<PositionRow> rows) {
    List<Position> out = new ArrayList<>();
    for (PositionRow r : rows) {
      if (!accountId.equals(r.account()) || r.contract() == null) {
        continue;
      }
      Contract c = r.contract();
      out.add(
          new Position(
              Broker.IBKR,
              accountId,
              Integer.toString(c.conid()),
              c.symbol(),
              c.localSymbol(),
              c.getSecType(),
              c.exchange(),
              c.primaryExch(),
              c.currency(),
              quantity(r.position(), c),
              cost(r.averageCost())));
    }
    return List.copyOf(out);
  }

  /**
   * Builds the summary for one account out of an "All"-group batch (rows of other accounts are
   * skipped). The raw tag map is kept for auditing, minus {@code $LEDGER-AccountOrGroup} and any
   * value containing the account number, so nothing downstream (database, API, logs) ever sees it.
   * Numbers stay as the broker's decimal strings parsed into BigDecimal; nothing is converted or
   * recomputed.
   */
  public static AccountSummary summary(
      String accountId, List<SummaryRow> rows, Instant receivedAt) {
    Map<String, String> raw = new LinkedHashMap<>();
    String currency = null;
    for (SummaryRow r : rows) {
      if (!accountId.equals(r.account()) || r.tag() == null) {
        continue;
      }
      String value = r.value() == null ? "" : r.value().trim();
      if (ACCOUNT_OR_GROUP.equals(r.tag()) || value.contains(accountId)) {
        continue; // never keep any value that contains the account number
      }
      raw.put(r.tag(), value);
      if ("NetLiquidation".equals(r.tag()) && r.currency() != null && !r.currency().isBlank()) {
        currency = r.currency().trim();
      }
    }
    if (raw.isEmpty()) {
      throw new GatewayException(Broker.IBKR, 0, "账户汇总里没有目标账户的数据（账户不在受管列表？）", false);
    }
    return new AccountSummary(
        Broker.IBKR,
        accountId,
        receivedAt,
        currency,
        number(raw, "NetLiquidation"),
        number(raw, "TotalCashValue"),
        number(raw, "$LEDGER-StockMarketValue"),
        number(raw, "GrossPositionValue"),
        number(raw, "AvailableFunds"),
        number(raw, "BuyingPower"),
        number(raw, "ExcessLiquidity"),
        number(raw, "$LEDGER-UnrealizedPnL"),
        number(raw, "$LEDGER-RealizedPnL"),
        number(raw, "$LEDGER-NetDividend"),
        number(raw, "$LEDGER-AccruedCash"),
        raw);
  }

  /**
   * Fails on an invalid quantity: recording it as 0 would read as a fully closed position, which is
   * worse than failing.
   */
  static BigDecimal quantity(Decimal d, Contract c) {
    if (d == null || !d.isValid()) {
      throw new IllegalStateException("持仓数量无效：" + c.symbol() + " conId=" + c.conid());
    }
    return d.value();
  }

  /** TWS uses Double.MAX_VALUE for "unset". */
  static BigDecimal cost(double v) {
    return Double.isNaN(v) || Double.isInfinite(v) || v == Double.MAX_VALUE
        ? null
        : BigDecimal.valueOf(v);
  }

  static BigDecimal number(Map<String, String> raw, String tag) {
    String v = raw.get(tag);
    if (v == null || v.isBlank()) {
      return null;
    }
    try {
      return new BigDecimal(v);
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
