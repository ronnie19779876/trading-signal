package org.jdkxx.trader.gateway.ibkr;

import com.ib.client.Bar;
import com.ib.client.Contract;
import com.ib.client.ContractDetails;
import com.ib.client.Decimal;
import com.ib.client.DefaultEWrapper;
import com.ib.client.TickAttrib;
import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.gateway.RequestRejectedException;
import org.jdkxx.trader.gateway.ibkr.mapper.IbkrAccounts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The single callback entry point of the TWS API. Connection-level messages go to {@link
 * ConnectionEvents}; callbacks that carry a reqId go to {@link IbkrRequestRegistry} (one-shot
 * requests) and {@link IbkrSubscriptions} (standing subscriptions). These methods all run on the
 * pump thread and only dispatch: nothing blocking or slow happens here.
 *
 * <p>Adding a new TWS request: hand its data callback to {@code registry.item} and its end callback
 * to {@code registry.complete}; requests without a reqId use a waiter queue in {@link
 * IbkrConnection} (see {@code currentTime}).
 */
final class IbkrWrapper extends DefaultEWrapper {

  private static final Logger log = LoggerFactory.getLogger(IbkrWrapper.class);

  interface ConnectionEvents {
    void onConnectAck();

    void onNextValidId(int orderId);

    void onManagedAccounts(String accounts);

    void onCurrentTime(long epochSeconds);

    void onConnectionClosed();

    void onSystemMessage(int code, String message);
  }

  private final ConnectionEvents events;
  private final IbkrRequestRegistry registry;
  private final IbkrSubscriptions subscriptions;

  IbkrWrapper(
      ConnectionEvents events, IbkrRequestRegistry registry, IbkrSubscriptions subscriptions) {
    this.events = events;
    this.registry = registry;
    this.subscriptions = subscriptions;
  }

  @Override
  public void connectAck() {
    events.onConnectAck();
  }

  @Override
  public void nextValidId(int orderId) {
    events.onNextValidId(orderId);
  }

  @Override
  public void managedAccounts(String accountsList) {
    events.onManagedAccounts(accountsList);
  }

  @Override
  public void currentTime(long time) {
    events.onCurrentTime(time);
  }

  @Override
  public void connectionClosed() {
    events.onConnectionClosed();
  }

  /**
   * Error routing, in order: a pending one-shot request with this reqId fails; otherwise a standing
   * subscription with this reqId gets the error; everything else (id -1, orderIds, unknown reqIds)
   * is a system message. Note that any code addressed to a pending request fails it, warnings
   * included.
   */
  @Override
  public void error(
      int id, long errorTime, int errorCode, String errorMsg, String advancedOrderRejectJson) {
    if (errorCode == 2100 || errorCode == 2101) {
      // Account-updates feed unsubscribed (2100) / held by another client (2101): these carry no
      // reqId, so they are forwarded to that channel and also logged as a system message as usual
      subscriptions.error(
          IbkrSubscriptions.PORTFOLIO,
          new RequestRejectedException(Broker.IBKR, errorCode, errorMsg));
    }
    if (IbkrRequestRegistry.isRequestId(id) && registry.isPending(id)) {
      registry.fail(id, new RequestRejectedException(Broker.IBKR, errorCode, errorMsg));
      return;
    }
    if (IbkrRequestRegistry.isRequestId(id)
        && subscriptions.error(
            id, new RequestRejectedException(Broker.IBKR, errorCode, errorMsg))) {
      return;
    }
    events.onSystemMessage(errorCode, errorMsg);
  }

  @Override
  public void error(Exception e) {
    log.warn("TWS 客户端异常：{}", e.toString());
    events.onSystemMessage(0, e.toString());
  }

  @Override
  public void error(String str) {
    log.warn("TWS 客户端消息：{}", str);
  }

  @Override
  public void contractDetails(int reqId, ContractDetails contractDetails) {
    registry.item(reqId, contractDetails);
  }

  @Override
  public void contractDetailsEnd(int reqId) {
    registry.complete(reqId);
  }

  /**
   * Historical daily bars (quantitative selection, §25.6): a one-shot request, bars arrive one at a
   * time and End closes it.
   */
  @Override
  public void historicalData(int reqId, Bar bar) {
    registry.item(reqId, bar);
  }

  @Override
  public void historicalDataEnd(int reqId, String startDateStr, String endDateStr) {
    registry.complete(reqId);
  }

  /** Earliest data time (quantitative selection, §25.6): a single callback is the whole result. */
  @Override
  public void headTimestamp(int reqId, String headTimestamp) {
    registry.item(reqId, headTimestamp);
    registry.complete(reqId);
  }

  // Positions and account summary exist both as one-shot queries (registry) and as standing
  // subscriptions (live account): each side recognizes its own reqIds and ignores the rest, so both
  // are always offered the row.

  @Override
  public void positionMulti(
      int reqId, String account, String modelCode, Contract contract, Decimal pos, double avgCost) {
    IbkrAccounts.PositionRow row = new IbkrAccounts.PositionRow(account, contract, pos, avgCost);
    registry.item(reqId, row);
    subscriptions.item(reqId, row);
  }

  @Override
  public void positionMultiEnd(int reqId) {
    registry.complete(reqId);
    subscriptions.end(reqId);
  }

  /**
   * After a cancel the broker still pushes one or two more rows (measured); the request has ended
   * by then and the registry ignores them.
   */
  @Override
  public void accountSummary(int reqId, String account, String tag, String value, String currency) {
    IbkrAccounts.SummaryRow row = new IbkrAccounts.SummaryRow(account, tag, value, currency);
    registry.item(reqId, row);
    subscriptions.item(reqId, row);
  }

  @Override
  public void accountSummaryEnd(int reqId) {
    registry.complete(reqId);
    subscriptions.end(reqId);
  }

  /**
   * Account-updates feed (3.4.6): one row per position; accountDownloadEnd once the first batch is
   * complete; after that a push whenever something changes or roughly every 3 minutes.
   */
  @Override
  public void updatePortfolio(
      Contract contract,
      Decimal position,
      double marketPrice,
      double marketValue,
      double averageCost,
      double unrealizedPNL,
      double realizedPNL,
      String accountName) {
    subscriptions.item(
        IbkrSubscriptions.PORTFOLIO,
        new IbkrAccounts.PortfolioRow(
            accountName, contract, position, averageCost, unrealizedPNL, marketPrice, marketValue));
  }

  @Override
  public void accountDownloadEnd(String accountName) {
    subscriptions.end(IbkrSubscriptions.PORTFOLIO);
  }

  @Override
  public void pnl(int reqId, double dailyPnL, double unrealizedPnL, double realizedPnL) {
    subscriptions.item(reqId, new IbkrAccounts.PnlRow(dailyPnL, unrealizedPnL, realizedPnL));
  }

  @Override
  public void pnlSingle(
      int reqId,
      Decimal pos,
      double dailyPnL,
      double unrealizedPnL,
      double realizedPnL,
      double value) {
    subscriptions.item(
        reqId, new IbkrAccounts.PnlSingleRow(pos, dailyPnL, unrealizedPnL, realizedPnL, value));
  }

  /**
   * Price ticks: the live account subscribes to market data for held instruments only; the
   * subscriber picks the tick types it cares about.
   */
  @Override
  public void tickPrice(int tickerId, int field, double price, TickAttrib attrib) {
    subscriptions.item(tickerId, new IbkrAccounts.TickRow(field, price));
  }

  /**
   * The broker reports whether this market data is real-time (1) or downgraded to delayed (3 / 4).
   */
  @Override
  public void marketDataType(int reqId, int marketDataType) {
    subscriptions.item(reqId, new IbkrAccounts.MarketDataTypeRow(marketDataType));
  }
}
