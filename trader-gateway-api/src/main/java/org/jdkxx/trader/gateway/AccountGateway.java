package org.jdkxx.trader.gateway;

import org.jdkxx.trader.domain.AccountSummary;
import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.domain.Position;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 账户端口（第 3 期）：持仓与资金汇总，全部只读。本期只有盈透实现。
 *
 * <p>accountId 是券商原始账户号，由调用方从 {@link BrokerGateway#accounts()} 取得；
 * 实现与调用方都不得把它写进日志、异常消息或接口返回。
 * 两个请求都是"请求 → 收齐 → 立刻取消"的一次性查询，不保留订阅。
 */
public interface AccountGateway {

    Broker broker();

    /** 当前全部持仓，券商原样（可能含数量为 0 的当天清仓条目，由调用方决定是否保留）。 */
    CompletableFuture<List<Position>> positions(String accountId);

    /** 资金汇总。同一账户并发调用会合并成一次券商请求。 */
    CompletableFuture<AccountSummary> accountSummary(String accountId);
}
