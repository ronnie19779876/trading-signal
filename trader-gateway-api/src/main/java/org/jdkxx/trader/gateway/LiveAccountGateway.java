package org.jdkxx.trader.gateway;

import org.jdkxx.trader.domain.Broker;

/**
 * 实时账户端口（3.0.2 起，只读）：常驻订阅资金汇总、账户盈亏、持仓与逐只盈亏，推送给监听器。
 *
 * <p>与 {@link AccountGateway} 的一次性查询分开：那边"请求 → 收齐 → 取消"给每日快照用，这边按需开、闲置关。
 * 盈透的账户汇总订阅每个客户端最多 2 个（2026-09-19 实测 322），这里占 1 个，快照的一次性请求占另 1 个。
 *
 * <p>断线后订阅随会话失效；只要没有 {@link #stopLive()}，重连后由适配器自动重订。
 * accountId 是券商原始账户号：实现与调用方都不得把它写进日志、异常消息或接口返回。
 */
public interface LiveAccountGateway {

    Broker broker();

    /**
     * 开始订阅。已在订阅同一账户时直接返回；换账户时先退订旧的。未连接时记下意图，连上后再订。
     */
    void startLive(String accountId, LiveAccountListener listener);

    /** 退订全部并忘掉意图。未在订阅时什么也不做。 */
    void stopLive();

    /** 当前是否有订阅意图（不代表数据已到）。 */
    boolean liveActive();
}
