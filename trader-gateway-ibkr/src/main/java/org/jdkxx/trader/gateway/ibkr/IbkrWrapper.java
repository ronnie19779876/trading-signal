package org.jdkxx.trader.gateway.ibkr;

import com.ib.client.Contract;
import com.ib.client.ContractDetails;
import com.ib.client.Decimal;
import com.ib.client.DefaultEWrapper;
import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.gateway.RequestRejectedException;
import org.jdkxx.trader.gateway.ibkr.mapper.IbkrAccounts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TWS API 的唯一回调入口。连接级消息交给 {@link ConnectionEvents}，带 reqId 的回调交给
 * {@link IbkrRequestRegistry}。这里的方法都在泵线程上执行，只做分发，不做任何阻塞或耗时的事。
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

    IbkrWrapper(ConnectionEvents events, IbkrRequestRegistry registry, IbkrSubscriptions subscriptions) {
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

    @Override
    public void error(int id, long errorTime, int errorCode, String errorMsg, String advancedOrderRejectJson) {
        if (IbkrRequestRegistry.isRequestId(id) && registry.isPending(id)) {
            registry.fail(id, new RequestRejectedException(Broker.IBKR, errorCode, errorMsg));
            return;
        }
        if (IbkrRequestRegistry.isRequestId(id) && subscriptions.error(id, new RequestRejectedException(Broker.IBKR, errorCode, errorMsg))) {
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

    // 持仓与账户汇总既有一次性查询（注册表）也有常驻订阅（实时账户）：两边按 reqId 各认各的，不认识的忽略。

    @Override
    public void positionMulti(int reqId, String account, String modelCode, Contract contract, Decimal pos, double avgCost) {
        IbkrAccounts.PositionRow row = new IbkrAccounts.PositionRow(account, contract, pos, avgCost);
        registry.item(reqId, row);
        subscriptions.item(reqId, row);
    }

    @Override
    public void positionMultiEnd(int reqId) {
        registry.complete(reqId);
        subscriptions.end(reqId);
    }

    /** 取消后券商还会再推一两条（实测），此时请求已结束，注册表会忽略。 */
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

    @Override
    public void pnl(int reqId, double dailyPnL, double unrealizedPnL, double realizedPnL) {
        subscriptions.item(reqId, new IbkrAccounts.PnlRow(dailyPnL, unrealizedPnL, realizedPnL));
    }

    @Override
    public void pnlSingle(int reqId, Decimal pos, double dailyPnL, double unrealizedPnL, double realizedPnL, double value) {
        subscriptions.item(reqId, new IbkrAccounts.PnlSingleRow(pos, dailyPnL, unrealizedPnL, realizedPnL, value));
    }
}
